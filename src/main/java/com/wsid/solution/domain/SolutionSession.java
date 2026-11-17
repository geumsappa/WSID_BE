package com.wsid.solution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * solution_sessions 테이블. 피해 대응 한 건의 현재 상태예요.
 * 확인한 사실과 적용된 카드 코드·버전만 저장하고, 피해 설명 원문은 저장하지 않아요.
 * 소유자는 회원(user_id)과 비회원(anonymous_owner_id) 중 정확히 하나예요(DB CHECK 제약).
 */
@Entity
@Table(name = "solution_sessions")
public class SolutionSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Column(name = "user_id", updatable = false)
    private Long userId;

    /** 비회원 상담 소유자. 서버가 HTTP 세션에 발급한 UUID이며 응답에 노출하지 않아요. */
    @Column(name = "anonymous_owner_id", updatable = false)
    private UUID anonymousOwnerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SolutionStatus status;

    @Column(name = "fraud_type_code", length = 50)
    private String fraudTypeCode;

    /** 확인한 사실. 예: {"transferredMoney":"YES","sharedInformation":"UNKNOWN","installedApp":"UNKNOWN","contactContinues":"NO"} */
    @Column(name = "facts_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String factsJson;

    /** 적용된 대응 카드 참조(본문 복사 없음). 예: [{"code":"TRANSFER_RESPONSE","version":1}] */
    @Column(name = "card_codes_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String cardCodesJson;

    /** 아직 답을 받지 못한 다음 질문. 예: {"factKey":"sharedInformation","content":"...","options":["YES","NO","UNKNOWN"]} */
    @Column(name = "next_question_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String nextQuestionJson;

    @Version
    @Column(name = "state_version", nullable = false)
    private Integer stateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SolutionSession() {
        // JPA용
    }

    /**
     * SOL-01: 초기 상태를 모두 채운 새 상담. 저장 전에 완성해 첫 INSERT의 state_version이 0이 되게 해요.
     * fraud_type_code는 시작 시 추정하지 않아 null이에요.
     */
    public static SolutionSession start(SolutionOwner owner, SolutionFacts facts, List<ResponseCard> cards,
            SolutionQuestion nextQuestion, Instant now) {
        if (owner == null || facts == null || cards == null || now == null) {
            throw new IllegalArgumentException("상담 초기 상태가 올바르지 않아요.");
        }
        SolutionSession session = new SolutionSession();
        session.userId = owner.userId();
        session.anonymousOwnerId = owner.anonymousId();
        session.status = SolutionStatus.ACTIVE;
        session.factsJson = JSON.writeValueAsString(facts.toKeyMap());
        session.cardCodesJson = JSON.writeValueAsString(cards.stream().map(card -> {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("code", card.code());
            ref.put("version", card.version());
            return ref;
        }).toList());
        session.nextQuestionJson = nextQuestion == null ? null : JSON.writeValueAsString(nextQuestion);
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    // TODO(SOL-03, SOL-04): 사실 갱신·사용자 확정값 보호·카드 재판정을 여기에 추가해요.

    /** SOL-02: 저장된 사실. 4개 키·허용 값이 아니면 조용히 고치지 않고 예외를 던져요. */
    public SolutionFacts readFacts() {
        try {
            JsonNode node = JSON.readTree(factsJson);
            if (!node.isObject()) throw new IllegalArgumentException();
            var values = new HashMap<String, String>();
            for (String key : node.propertyNames()) {
                JsonNode value = node.get(key);
                if (!value.isString()) throw new IllegalArgumentException();
                values.put(key, value.stringValue());
            }
            return SolutionFacts.fromStrings(values);
        } catch (RuntimeException e) {
            throw corrupted("facts_json");
        }
    }

    /** SOL-02: 저장된 카드 참조. 저장 순서를 유지해요. */
    public List<CardReference> readCardReferences() {
        try {
            JsonNode node = JSON.readTree(cardCodesJson);
            if (!node.isArray()) throw new IllegalArgumentException();
            var refs = new ArrayList<CardReference>();
            for (JsonNode item : node) {
                if (!item.isObject() || item.size() != 2 || !item.path("code").isString() || !item.path("version").isInt()) {
                    throw new IllegalArgumentException();
                }
                refs.add(new CardReference(item.get("code").stringValue(), item.get("version").intValue()));
            }
            return List.copyOf(refs);
        } catch (RuntimeException e) {
            throw corrupted("card_codes_json");
        }
    }

    /** SOL-02: 저장된 다음 질문. 없으면 null이에요. */
    public SolutionQuestion readNextQuestion() {
        if (nextQuestionJson == null) return null;
        try {
            JsonNode node = JSON.readTree(nextQuestionJson);
            if (!node.isObject() || node.size() != 3 || !node.path("factKey").isString()
                    || !node.path("content").isString() || !node.path("options").isArray()) {
                throw new IllegalArgumentException();
            }
            String factKey = node.get("factKey").stringValue();
            String content = node.get("content").stringValue();
            var options = new ArrayList<String>();
            for (JsonNode option : node.get("options")) {
                if (!option.isString()) throw new IllegalArgumentException();
                options.add(option.stringValue());
            }
            if (SolutionFact.fromKey(factKey).isEmpty() || content.isBlank() || !options.equals(SolutionQuestion.OPTIONS)) {
                throw new IllegalArgumentException();
            }
            return new SolutionQuestion(factKey, content, options);
        } catch (RuntimeException e) {
            throw corrupted("next_question_json");
        }
    }

    /** 저장값 자체는 메시지에 넣지 않고 상담 ID와 컬럼 이름만 남겨요. */
    private IllegalStateException corrupted(String column) {
        return new IllegalStateException("상담 저장값 형식이 올바르지 않아요: solutionId=" + id + ", column=" + column);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getAnonymousOwnerId() {
        return anonymousOwnerId;
    }

    public SolutionStatus getStatus() {
        return status;
    }

    public String getFraudTypeCode() {
        return fraudTypeCode;
    }

    public String getFactsJson() {
        return factsJson;
    }

    public String getCardCodesJson() {
        return cardCodesJson;
    }

    public String getNextQuestionJson() {
        return nextQuestionJson;
    }

    public int getStateVersion() {
        return stateVersion == null ? 0 : stateVersion;
    }
}
