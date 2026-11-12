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
import org.hibernate.annotations.ColumnTransformer;

/**
 * solution_sessions 테이블. 피해 대응 한 건의 현재 상태예요.
 * 확인한 사실과 적용된 카드 코드만 저장하고, 피해 설명 원문은 저장하지 않아요.
 */
@Entity
@Table(name = "solution_sessions")
public class SolutionSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SolutionStatus status;

    @Column(name = "fraud_type_code", length = 50)
    private String fraudTypeCode;

    /** 확인한 사실. 예: {"송금": "아니오", "앱 설치": "예", "정보 제공": "모름"} */
    @Column(name = "facts_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String factsJson;

    /** 적용된 대응 카드 코드 목록. 예: ["CARD_APP_INSTALLED"] */
    @Column(name = "card_codes_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String cardCodesJson;

    /** 아직 답을 받지 못한 다음 질문 */
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

    /** SOL-003: 피해 대응을 시작해요. */
    public static SolutionSession start(long userId, Instant now) {
        SolutionSession session = new SolutionSession();
        session.userId = userId;
        session.status = SolutionStatus.ACTIVE;
        session.factsJson = "{}";
        session.cardCodesJson = "[]";
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    // TODO(SOL-003, SOL-004, SOL-007): 사실 갱신, 대응 규칙 적용, 긴급 카드 우선 표시 규칙을 여기에 추가해요.

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
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
