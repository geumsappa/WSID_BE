package com.wsid.solution.domain;

import com.wsid.ai.dto.SolutionAssistCondition;
import com.wsid.ai.dto.SolutionAssistConfirmedCondition;
import com.wsid.ai.dto.SolutionAssistIntent;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 답을 기다리는 assist 추가 질문. No original question or AI prose. Budget is consumed when a question is issued.
 * 묻는 대상은 네 사실 하나(fact) 또는 근거 문단 인용 조건 하나(condition, v1.2) 중 하나예요.
 * confirmedConditions는 같은 질문 흐름에서 이미 답한 조건(문단 코드·원문 인용·답)이에요.
 * conditionContent는 조건 질문의 AI 문장이에요. 처음과 재조회에 같은 문장을 보여 주려고 답할 때까지만 두고,
 * 답하면 pending과 함께 지워져요. 메시지 기록에는 남기지 않아요. 네 사실 질문이면 null이에요.
 */
public record PendingAssist(UUID questionId, SolutionFact fact, SolutionAssistCondition condition,
        String conditionContent, SolutionAssistIntent questionIntent, List<String> askedFactKeys,
        List<SolutionAssistConfirmedCondition> confirmedConditions, int remainingClarifications) {

    /** v1 모양(네 사실 질문). 옛 pending_assist_json도 이 모양으로 읽혀요. */
    public PendingAssist(UUID questionId, SolutionFact fact, SolutionAssistIntent questionIntent,
            List<String> askedFactKeys, int remainingClarifications) {
        this(questionId, fact, null, null, questionIntent, askedFactKeys, List.of(), remainingClarifications);
    }

    public PendingAssist {
        if (confirmedConditions == null) confirmedConditions = List.of();
        if (questionId == null || questionIntent == null || askedFactKeys == null
                || (fact == null) == (condition == null)
                || (condition == null) != (conditionContent == null)
                || (conditionContent != null && (conditionContent.isBlank()
                        || conditionContent.length() > com.wsid.ai.dto.SolutionAssistClarification.MAX_CONTENT_LENGTH))
                || remainingClarifications < 0 || remainingClarifications > 2
                || askedFactKeys.size() + confirmedConditions.size() + (condition == null ? 0 : 1) != 3 - remainingClarifications
                || new HashSet<>(askedFactKeys).size() != askedFactKeys.size()
                || (fact != null && !askedFactKeys.contains(fact.key()))
                || askedFactKeys.stream().anyMatch(k -> SolutionFact.fromKey(k).isEmpty())
                || confirmedConditions.stream().anyMatch(java.util.Objects::isNull)
                || (condition != null && confirmedConditions.stream()
                        .anyMatch(c -> c.conditionId().equals(condition.conditionId())))) {
            throw new IllegalArgumentException("추가 질문 상태가 올바르지 않아요.");
        }
        askedFactKeys = List.copyOf(askedFactKeys);
        confirmedConditions = SolutionAssistConfirmedCondition.requireList(confirmedConditions);
    }

    public boolean asksCondition() {
        return condition != null;
    }
}
