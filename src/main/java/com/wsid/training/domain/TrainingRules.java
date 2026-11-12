package com.wsid.training.domain;

import java.time.Duration;

/**
 * 훈련 진행 규칙의 설정값. application.yml의 wsid.training에서 읽어요.
 *
 * @param keySceneDeadlineTurn 이 턴까지 핵심 장면이 안 나왔으면 백엔드가 강제로 표시해요 (SIM-011)
 * @param maxSimulationTurns   가상 훈련의 AI 메시지 최대 개수
 * @param sessionTtl           마지막 활동 뒤 이 시간이 지나면 세션이 만료돼요
 */
public record TrainingRules(int keySceneDeadlineTurn, int maxSimulationTurns, Duration sessionTtl) {

    public TrainingRules {
        if (keySceneDeadlineTurn < 1 || maxSimulationTurns <= keySceneDeadlineTurn) {
            throw new IllegalArgumentException("핵심 장면 기한은 1 이상이고 최대 턴보다 작아야 해요.");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("세션 만료 시간은 0보다 커야 해요.");
        }
    }
}
