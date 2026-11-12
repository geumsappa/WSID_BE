package com.wsid.training.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 훈련 상태 머신의 규칙을 검증해요. DB, Spring, AI 없이 돌아가요.
 * 규칙을 바꾸면 여기 테스트부터 고쳐요.
 */
class TrainingSessionTest {

    private static final TrainingRules RULES = new TrainingRules(12, 15, Duration.ofHours(24));
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final long USER_ID = 1L;

    /** AI가 평범한 대사를 제안한 턴 */
    private static final TurnInput NORMAL = new TurnInput(UserAction.ASK_QUESTION, false, true);
    /** AI가 핵심 장면을 꺼내자고 제안한 턴 */
    private static final TurnInput SUGGEST_KEY_SCENE = new TurnInput(UserAction.ASK_QUESTION, true, true);
    /** 가드레일을 통과하지 못했거나 AI 호출이 실패한 턴 */
    private static final TurnInput AI_UNUSABLE = new TurnInput(UserAction.UNCLEAR, false, false);
    /** 사용자가 연락을 끊고 공식 채널로 확인한 턴 */
    private static final TurnInput END_CONTACT = new TurnInput(UserAction.END_CONTACT_AND_VERIFY, false, true);

    private static TrainingSession simulationSession() {
        TrainingSession session = TrainingSession.start(USER_ID, RULES, NOW);
        session.beginSimulation("USED_GOODS_TRADE", RULES, NOW);
        return session;
    }

    private static void advanceTo(TrainingSession session, int turn) {
        while (session.getSimulationTurnCount() < turn) {
            session.applyTurn(NORMAL, RULES, NOW);
        }
    }

    @Test
    @DisplayName("새 훈련은 일상 대화 단계에서 시작하고, 가상 훈련의 첫 대사가 1턴이다")
    void startsInDiscoveryThenSimulationAtTurnOne() {
        TrainingSession session = TrainingSession.start(USER_ID, RULES, NOW);
        assertEquals(TrainingPhase.DISCOVERY, session.getPhase());
        assertEquals(TrainingStatus.ACTIVE, session.getStatus());
        assertEquals(0, session.getSimulationTurnCount());
        assertEquals(USER_ID, session.getUserId());

        session.beginSimulation("USED_GOODS_TRADE", RULES, NOW);
        assertEquals(TrainingPhase.SIMULATION, session.getPhase());
        assertEquals(1, session.getSimulationTurnCount());
        assertEquals(1, session.getDiscoveryTurnCount());
        assertEquals("USED_GOODS_TRADE", session.getFraudTypeCode());
    }

    @Test
    @DisplayName("일상 대화 단계에서는 가상 훈련 턴을 적용할 수 없다")
    void rejectsTurnDuringDiscovery() {
        TrainingSession session = TrainingSession.start(USER_ID, RULES, NOW);
        assertThrows(IllegalStateException.class, () -> session.applyTurn(NORMAL, RULES, NOW));
    }

    @Test
    @DisplayName("AI가 제안하면 기한 전에도 AI 대사로 핵심 장면을 보여준다")
    void showsKeySceneWhenAiSuggests() {
        TrainingSession session = simulationSession();
        advanceTo(session, 4);

        TurnOutcome outcome = session.applyTurn(SUGGEST_KEY_SCENE, RULES, NOW);

        assertTrue(outcome.keyScene());
        assertEquals(TextSource.AI, outcome.textSource());
        assertEquals(5, session.getSimulationTurnCount());
        assertTrue(session.isKeySceneShown());
    }

    @Test
    @DisplayName("12턴까지 핵심 장면이 안 나왔으면 검토된 장면을 강제로 보여준다")
    void forcesReviewedKeySceneAtDeadline() {
        TrainingSession session = simulationSession();
        advanceTo(session, 11);
        assertFalse(session.isKeySceneShown());

        TurnOutcome outcome = session.applyTurn(NORMAL, RULES, NOW);

        assertEquals(12, session.getSimulationTurnCount());
        assertTrue(outcome.keyScene());
        assertEquals(TextSource.REVIEWED, outcome.textSource());
    }

    @Test
    @DisplayName("AI가 핵심 장면을 제안했지만 대사를 쓸 수 없으면 검토된 장면으로 대체한다")
    void usesReviewedKeySceneWhenAiTextUnusable() {
        TrainingSession session = simulationSession();

        TurnOutcome outcome = session.applyTurn(new TurnInput(UserAction.ASK_QUESTION, true, false), RULES, NOW);

        assertTrue(outcome.keyScene());
        assertEquals(TextSource.REVIEWED, outcome.textSource());
    }

    @Test
    @DisplayName("AI가 매 턴 제안해도 핵심 장면은 한 번만 나온다")
    void showsKeySceneOnlyOnce() {
        TrainingSession session = simulationSession();
        int keyScenes = 0;

        while (session.getStatus() == TrainingStatus.ACTIVE) {
            if (session.applyTurn(SUGGEST_KEY_SCENE, RULES, NOW).keyScene()) {
                keyScenes++;
            }
        }

        assertEquals(1, keyScenes);
    }

    @Test
    @DisplayName("사용자가 연락을 끊고 검증하면 일찍 마친다")
    void finishesEarlyWhenUserEndsContactAndVerifies() {
        TrainingSession session = simulationSession();
        advanceTo(session, 3);

        TurnOutcome outcome = session.applyTurn(END_CONTACT, RULES, NOW);

        assertTrue(outcome.finished());
        assertEquals(TrainingPhase.REPORT, session.getPhase());
        assertEquals(TrainingStatus.COMPLETED, session.getStatus());
        assertEquals(NOW, session.getCompletedAt());
        assertFalse(session.isKeySceneShown());
    }

    @Test
    @DisplayName("15턴에 도달하면 훈련을 마친다")
    void finishesAtMaxTurns() {
        TrainingSession session = simulationSession();
        advanceTo(session, 14);
        assertEquals(TrainingStatus.ACTIVE, session.getStatus());

        TurnOutcome outcome = session.applyTurn(NORMAL, RULES, NOW);

        assertEquals(15, session.getSimulationTurnCount());
        assertTrue(outcome.finished());
        assertEquals(TrainingPhase.REPORT, outcome.phase());
    }

    @Test
    @DisplayName("AI 대사를 쓸 수 없으면 검토된 문구로 이어간다")
    void fallsBackToReviewedTextWhenAiUnusable() {
        TrainingSession session = simulationSession();

        TurnOutcome outcome = session.applyTurn(AI_UNUSABLE, RULES, NOW);

        assertEquals(TextSource.REVIEWED, outcome.textSource());
        assertFalse(outcome.finished());
        assertEquals(TrainingPhase.SIMULATION, outcome.phase());
    }

    @Test
    @DisplayName("끝난 훈련에는 턴을 더 적용할 수 없다")
    void rejectsTurnAfterFinished() {
        TrainingSession session = simulationSession();
        session.applyTurn(END_CONTACT, RULES, NOW);

        assertThrows(IllegalStateException.class, () -> session.applyTurn(NORMAL, RULES, NOW));
    }

    @Test
    @DisplayName("마지막 활동 뒤 24시간이 지나면 만료되고, 활동하면 만료 시각이 미뤄진다")
    void expiresAfterInactivityAndActivityExtendsIt() {
        TrainingSession session = simulationSession();
        assertFalse(session.isExpired(NOW.plus(Duration.ofHours(23))));
        assertTrue(session.isExpired(NOW.plus(Duration.ofHours(24))));

        Instant later = NOW.plus(Duration.ofHours(10));
        session.applyTurn(NORMAL, RULES, later);

        assertFalse(session.isExpired(NOW.plus(Duration.ofHours(24))));
        assertEquals(later.plus(Duration.ofHours(24)), session.getExpiresAt());
    }

    @Test
    @DisplayName("끝난 훈련은 시간이 지나도 만료로 보지 않는다")
    void completedSessionIsNotExpired() {
        TrainingSession session = simulationSession();
        session.applyTurn(END_CONTACT, RULES, NOW);

        assertFalse(session.isExpired(NOW.plus(Duration.ofDays(30))));
    }

    @Test
    @DisplayName("AI가 허용 목록 밖의 행동 코드를 보내면 UNCLEAR로 받는다")
    void unknownActionCodeBecomesUnclear() {
        assertEquals(UserAction.UNCLEAR, UserAction.fromCode("SOMETHING_ELSE"));
        assertEquals(UserAction.UNCLEAR, UserAction.fromCode(null));
        assertEquals(UserAction.REFUSE, UserAction.fromCode("REFUSE"));
    }
}
