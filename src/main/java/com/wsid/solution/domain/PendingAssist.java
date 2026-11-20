package com.wsid.solution.domain;

import com.wsid.ai.dto.SolutionAssistIntent;
import java.util.List;
import java.util.UUID;

/** No original question or AI prose. Budget is consumed when a question is issued. */
public record PendingAssist(UUID questionId, SolutionFact fact, SolutionAssistIntent questionIntent,
        List<String> askedFactKeys, int remainingClarifications) {
    public PendingAssist {
        if (questionId == null || fact == null || questionIntent == null || askedFactKeys == null
                || remainingClarifications < 0 || remainingClarifications > 2
                || askedFactKeys.size() != 3 - remainingClarifications
                || new java.util.HashSet<>(askedFactKeys).size() != askedFactKeys.size()
                || !askedFactKeys.contains(fact.key())
                || askedFactKeys.stream().anyMatch(k -> SolutionFact.fromKey(k).isEmpty())) {
            throw new IllegalArgumentException("추가 질문 상태가 올바르지 않아요.");
        }
        askedFactKeys = List.copyOf(askedFactKeys);
    }
}
