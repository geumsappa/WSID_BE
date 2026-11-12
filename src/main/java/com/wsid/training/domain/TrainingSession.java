package com.wsid.training.domain;

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
 * training_sessions 테이블. 훈련 한 건의 현재 상태이고, 턴 수·핵심 장면·종료를 정하는 규칙이 여기에 있어요.
 * AI는 제안만 하고, 다음 단계는 이 클래스가 정해요.
 *
 * 규칙 메서드는 DB나 Spring 없이 동작하므로 단위 테스트로 바로 검증할 수 있어요.
 */
@Entity
@Table(name = "training_sessions")
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TrainingPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrainingStatus status;

    @Column(name = "fraud_type_code", length = 50)
    private String fraudTypeCode;

    /** 일상 대화에서 확인한 사실 (SIM-002). JSON 문자열 */
    @Column(name = "context_facts", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String contextFacts;

    /** 이 훈련에 쓰는 시나리오 (SIM-005). JSON 문자열 */
    @Column(name = "scenario_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String scenarioJson;

    /** 모의 페이지 템플릿 코드 (SIM-014) */
    @Column(name = "mock_page_template_code", length = 50)
    private String mockPageTemplateCode;

    /** 진행 상태. FlowState를 JSON 문자열로 저장해요. */
    @Column(name = "flow_state_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String flowStateJson;

    /** 일상 대화에서 받은 사용자 답변 수 */
    @Column(name = "discovery_turn_count", nullable = false)
    private int discoveryTurnCount;

    /** 가상 훈련에서 AI(상대 역할)가 보낸 메시지 수. 시나리오 첫 대사가 1이에요. */
    @Column(name = "simulation_turn_count", nullable = false)
    private int simulationTurnCount;

    /** 저장할 때마다 올라가요. 화면이 옛 상태로 보낸 요청을 걸러내는 데 써요. */
    @Version
    @Column(name = "state_version", nullable = false)
    private Integer stateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TrainingSession() {
        // JPA용
    }

    /** SIM-001: 새 훈련을 일상 대화 단계에서 시작해요. */
    public static TrainingSession start(long userId, TrainingRules rules, Instant now) {
        TrainingSession session = new TrainingSession();
        session.userId = userId;
        session.phase = TrainingPhase.DISCOVERY;
        session.status = TrainingStatus.ACTIVE;
        session.flowStateJson = FlowState.initial().toJson();
        session.discoveryTurnCount = 0;
        session.simulationTurnCount = 0;
        session.createdAt = now;
        session.touch(rules, now);
        return session;
    }

    /** 일상 대화를 마치고 가상 훈련을 시작해요. 시나리오의 첫 대사가 가상 훈련 1턴이에요. */
    public void beginSimulation(String fraudTypeCode, TrainingRules rules, Instant now) {
        requireActive();
        if (phase != TrainingPhase.DISCOVERY) {
            throw new IllegalStateException("가상 훈련은 일상 대화 단계에서만 시작할 수 있어요.");
        }
        this.fraudTypeCode = fraudTypeCode;
        this.discoveryTurnCount++;
        this.phase = TrainingPhase.SIMULATION;
        this.simulationTurnCount = 1;
        touch(rules, now);
    }

    /**
     * 가상 훈련 한 턴을 적용해요. 규칙의 우선순위는 위에서 아래 순서예요.
     *
     * 1. 사용자가 이미 연락을 끊고 검증했으면 일찍 마쳐요.
     * 2. 최대 턴에 도달하면 마쳐요.
     * 3. 핵심 장면은 한 번만 나와요. AI가 제안하면 그때, 기한까지 안 나왔으면 강제로 보여줘요.
     * 4. AI 대사를 못 쓰면(가드레일 미통과, 호출 실패) 검토된 문구로 이어가요.
     */
    public TurnOutcome applyTurn(TurnInput input, TrainingRules rules, Instant now) {
        requireActive();
        if (phase != TrainingPhase.SIMULATION) {
            throw new IllegalStateException("가상 훈련 단계가 아니에요.");
        }
        simulationTurnCount++;
        touch(rules, now);

        if (input.userAction() == UserAction.END_CONTACT_AND_VERIFY) {
            return finish(now);
        }
        if (simulationTurnCount >= rules.maxSimulationTurns()) {
            return finish(now);
        }

        if (!isKeySceneShown()) {
            boolean deadlineReached = simulationTurnCount >= rules.keySceneDeadlineTurn();
            if (input.suggestKeyScene() || deadlineReached) {
                flowStateJson = flowState().withKeySceneShown().toJson();
                boolean useAiText = input.suggestKeyScene() && input.replyUsable();
                return new TurnOutcome(phase, true, useAiText ? TextSource.AI : TextSource.REVIEWED, false);
            }
        }
        return new TurnOutcome(phase, false, input.replyUsable() ? TextSource.AI : TextSource.REVIEWED, false);
    }

    /** 마지막 활동 뒤 만료 시간이 지났는지. 만료된 세션은 더 진행하지 않아요. */
    public boolean isExpired(Instant now) {
        return status == TrainingStatus.EXPIRED
                || (status == TrainingStatus.ACTIVE && !now.isBefore(expiresAt));
    }

    public boolean isKeySceneShown() {
        return flowState().keySceneShown();
    }

    private FlowState flowState() {
        return FlowState.fromJson(flowStateJson);
    }

    private TurnOutcome finish(Instant now) {
        phase = TrainingPhase.REPORT;
        status = TrainingStatus.COMPLETED;
        completedAt = now;
        return new TurnOutcome(phase, false, TextSource.REVIEWED, true);
    }

    private void requireActive() {
        if (status != TrainingStatus.ACTIVE) {
            throw new IllegalStateException("진행 중인 훈련이 아니에요.");
        }
    }

    private void touch(TrainingRules rules, Instant now) {
        lastActivityAt = now;
        expiresAt = now.plus(rules.sessionTtl());
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public TrainingPhase getPhase() {
        return phase;
    }

    public TrainingStatus getStatus() {
        return status;
    }

    public String getFraudTypeCode() {
        return fraudTypeCode;
    }

    public int getDiscoveryTurnCount() {
        return discoveryTurnCount;
    }

    public int getSimulationTurnCount() {
        return simulationTurnCount;
    }

    public int getStateVersion() {
        return stateVersion == null ? 0 : stateVersion;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
