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
                fact.question() + " 선택: " + selected.name(), requestId, now);
        message.factKey = fact.key();
        message.selectedCode = selected.name();
        return message;
    }

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
