package com.wsid.training.domain;

import java.time.Duration;
import java.util.List;

public record TrainingRules(int keySceneDeadlineTurn, int maxSimulationTurns, Duration sessionTtl,
        int discoveryBaseQuestions, int discoveryMaxQuestions, int discoveryMinAnswers,
        List<String> requiredFacts) {
    public TrainingRules {
        if (keySceneDeadlineTurn < 1 || maxSimulationTurns <= keySceneDeadlineTurn)
            throw new IllegalArgumentException("핵심 장면 기한은 1 이상이고 최대 턴보다 작아야 해요.");
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative())
            throw new IllegalArgumentException("세션 만료 시간은 0보다 커야 해요.");
        if (discoveryBaseQuestions < 1 || discoveryMaxQuestions < discoveryBaseQuestions
                || discoveryMinAnswers < 1 || (long) discoveryMinAnswers > (long) discoveryMaxQuestions + 1)
            throw new IllegalArgumentException("상황 파악 질문·답변 횟수 설정이 올바르지 않아요.");
        if (requiredFacts == null || requiredFacts.isEmpty() || requiredFacts.stream().anyMatch(k -> !ContextFacts.ALLOWED.contains(k)))
            throw new IllegalArgumentException("필수 사실은 허용된 키를 사용해야 해요.");
        requiredFacts = List.copyOf(requiredFacts);
    }
}
