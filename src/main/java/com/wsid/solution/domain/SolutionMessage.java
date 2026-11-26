package com.wsid.solution.domain;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * solution_messages 테이블.
 * safe_content에는 서버 고정 문구·선택 표현만 저장해요. 피해 설명 원문은 저장하지 않아요.
 */
@Entity
@Table(name = "solution_messages")
public class SolutionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "solution_id", nullable = false, updatable = false)
    private Long solutionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageSender sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_kind", nullable = false, length = 15)
    private InputKind inputKind;

    @Column(name = "safe_content", columnDefinition = "text")
    private String safeContent;

    @Column(name = "purpose", length = 32)
    private String purpose;
    @Column(name = "assist_question_id")
    private UUID assistQuestionId;

    /** SOL-06 사진 메시지의 purpose. 이후 assist 요청 history에 사진 턴으로 넣을 행을 고를 때 써요. */
    public static final String PHOTO_PURPOSE = "PHOTO";

    /**
     * LLM 문맥(assist history)으로만 쓰는 내용. SOL-02 조회 응답에는 내보내지 않아요.
     * 사진 메시지는 "[사진 · 종류] 요약: … / 사진 속 글자: (가린 글자)"를 담아요.
     */
    @Column(name = "assist_context", columnDefinition = "text")
    private String assistContext;

    /** SOL-06 사진 메시지. 화면 문구는 지금과 같고, 사진 내용은 assist_context에만 남겨요. */
    public static SolutionMessage photo(long id, int sequence, String safeContent, String assistContext,
            UUID requestId, Instant now) {
        if (assistContext == null || assistContext.isBlank()) throw new IllegalArgumentException("사진 문맥이 비어 있어요.");
        var m = of(id, MessageSender.USER, InputKind.TEXT, sequence, safeContent, requestId, now);
        m.purpose = PHOTO_PURPOSE;
        m.assistContext = assistContext;
        return m;
    }

    public String getAssistContext() { return assistContext; }

    public String getPurpose() { return purpose; }

    /** SOL-03 AI 답변 행의 purpose. 새로고침 복원(SOL-02)과 assist history에서 AI 턴으로 읽어요. */
    public static final String ANSWER_PURPOSE = "ANSWER";

    /**
     * SOL-03 자유 질문(QUESTION) 또는 사실 확인 질문의 답(CLARIFICATION_ANSWER).
     * 자유 질문은 질문 원문을 남겨 새로고침 때 그대로 보이고 이후 assist history로도 써요.
     * 민감정보 패턴은 저장 전에 SensitiveDescriptionGuard가 거부해요.
     */
    public static SolutionMessage assist(long id, int sequence, String purpose, UUID questionId, String question,
            SolutionFact fact, FactValue selected, UUID requestId, java.util.List<String> references, Instant now) {
        if (fact == null && (question == null || question.isBlank())) throw new IllegalArgumentException("질문 기록이 비어 있어요.");
        var m = fact == null ? of(id, MessageSender.USER, InputKind.TEXT, sequence, question.strip(), requestId, now)
                : choice(id, sequence, fact, selected, requestId, now);
        m.purpose = purpose;
        m.assistQuestionId = questionId;
        m.referenceCodesJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(references);
        return m;
    }

    /**
     * v1.2 조건 질문에 대한 답. "{조건 질문 문장} → 아니요"처럼 무엇을 물었고 어떻게 답했는지 남겨요.
     * fact_key는 NULL이라 네 사실 답변 이력과 섞이지 않아요.
     * reference_codes_json에는 조건 질문의 근거 코드를 먼저, 이어서 이 턴의 답변이 인용한 코드를 중복 없이 남겨요.
     */
    public static SolutionMessage conditionAnswer(long id, int sequence, String purpose, UUID questionId,
            String referenceCode, String conditionQuestion, FactValue selected, UUID requestId,
            java.util.List<String> answerReferences, Instant now) {
        if (referenceCode == null || selected == null || requestId == null || answerReferences == null) {
            throw new IllegalArgumentException("조건 답 기록이 올바르지 않아요.");
        }
        String asked = conditionQuestion == null || conditionQuestion.isBlank() ? "추가 확인" : conditionQuestion.strip();
        var m = of(id, MessageSender.USER, InputKind.OPTION, sequence, asked + " → " + selected.label(), requestId, now);
        m.purpose = purpose;
        m.assistQuestionId = questionId;
        m.selectedCode = selected.name();
        var codes = new java.util.LinkedHashSet<String>(); codes.add(referenceCode); codes.addAll(answerReferences);
        m.referenceCodesJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.List.copyOf(codes));
        return m;
    }

    /**
     * SOL-03 AI 응답 행(답변 또는 답변 불가 안내). 사용자 행 바로 다음 sequence로 같은 트랜잭션에서 저장해요.
     * client_request_id는 비워 둬서 요청 ID 중복 제약은 사용자 행만 받아요.
     */
    public static SolutionMessage aiReply(long id, int sequence, String text, java.util.List<String> references, Instant now) {
        if (text == null || text.isBlank() || references == null) throw new IllegalArgumentException("AI 응답 기록이 비어 있어요.");
        var m = of(id, MessageSender.AI, InputKind.TEXT, sequence, text.strip(), null, now);
        m.purpose = ANSWER_PURPOSE;
        m.referenceCodesJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.List.copyOf(references));
        return m;
    }

    /** 저장된 근거 코드 목록. 없으면 빈 목록이에요. */
    public java.util.List<String> getReferenceCodes() {
        if (referenceCodesJson == null || referenceCodesJson.isBlank()) return java.util.List.of();
        String[] codes = new tools.jackson.databind.ObjectMapper().readValue(referenceCodesJson, String[].class);
        return java.util.List.of(codes);
    }

    /** 사용자가 고른 선택지 코드 */
    @Column(name = "selected_code", length = 100)
    private String selectedCode;

    /** 선택이 가리킨 사실. 옛 메시지는 NULL이며 추정해 채우지 않아요. */
    @Column(name = "fact_key", length = 50)
    private String factKey;

    /** 이 메시지에서 안내한 카드·근거 문서 코드 목록 */
    @Column(name = "reference_codes_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String referenceCodesJson;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SolutionMessage() {
        // JPA용
    }

    public static SolutionMessage of(
            long solutionId,
            MessageSender sender,
            InputKind inputKind,
            int sequenceNo,
            String safeContent,
            UUID clientRequestId,
            Instant now) {
        SolutionMessage message = new SolutionMessage();
        message.solutionId = solutionId;
        message.sender = sender;
        message.inputKind = inputKind;
        message.sequenceNo = sequenceNo;
        message.safeContent = safeContent;
        message.clientRequestId = clientRequestId;
        message.createdAt = now;
        return message;
    }

    public static SolutionMessage choice(long solutionId, int sequenceNo, SolutionFact fact,
            FactValue selected, UUID requestId, Instant now) {
        if (fact == null || selected == null || requestId == null) {
            throw new IllegalArgumentException("선택 기록이 올바르지 않아요.");
        }
        SolutionMessage message = of(solutionId, MessageSender.USER, InputKind.OPTION, sequenceNo,
                fact.question() + " 선택: " + selected.label(), requestId, now);
        message.factKey = fact.key();
        message.selectedCode = selected.name();
        return message;
    }

    /**
     * SOL-04 직접 정정 기록. PATCH에는 요청 UUID가 없어 client_request_id는 NULL이에요.
     * OPTION·fact_key·selected_code를 채우므로 UNKNOWN 정정도 SOL-03의 답변 완료 이력으로 읽혀요.
     */
    public static SolutionMessage correction(long solutionId, int sequenceNo, SolutionFact fact, FactValue value, Instant now) {
        if (fact == null || value == null) throw new IllegalArgumentException("수정 기록이 올바르지 않아요.");
        SolutionMessage message = of(solutionId, MessageSender.USER, InputKind.OPTION, sequenceNo,
                CORRECTION_PREFIX + fact.question() + " 선택: " + value.label(), null, now);
        message.factKey = fact.key();
        message.selectedCode = value.name();
        return message;
    }

    public static final String CORRECTION_PREFIX = "사실 수정: ";

    public String getFactKey() { return factKey; }

    public UUID getClientRequestId() { return clientRequestId; }

    public Long getId() {
        return id;
    }

    public Long getSolutionId() {
        return solutionId;
    }

    public MessageSender getSender() {
        return sender;
    }

    public InputKind getInputKind() {
        return inputKind;
    }

    public String getSafeContent() {
        return safeContent;
    }

    public String getSelectedCode() {
        return selectedCode;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }
}
