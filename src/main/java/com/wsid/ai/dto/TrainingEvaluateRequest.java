package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

/** AI-05 요청. AI-04의 메시지 값 타입을 재사용하되 전체 평가용 개수 제한을 적용해요. */
public record TrainingEvaluateRequest(
        ScenarioGenerateResult.Scenario scenario,
        List<SimulationMessage> conversation,
        List<EvaluationAction> actions,
        @JsonIgnore UUID requestId) {

    public static final int MAX_MESSAGES = 40;
    public static final int MAX_ACTIONS = 100;

    public TrainingEvaluateRequest {
        if (scenario == null || requestId == null
                || conversation == null || conversation.size() > MAX_MESSAGES
                || actions == null || actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("평가 요청 형식이 올바르지 않아요.");
        }
        // List.of 계열은 contains(null)도 예외를 던질 수 있어 요소를 직접 검사해요.
        if (conversation.stream().anyMatch(message -> message == null)
                || actions.stream().anyMatch(action -> action == null)) {
            throw new IllegalArgumentException("평가 기록에 빈 요소가 있어요.");
        }
        if (conversation.isEmpty() && actions.isEmpty()) {
            throw new IllegalArgumentException("평가할 기록이 없어요.");
        }
        conversation = List.copyOf(conversation);
        actions = List.copyOf(actions);
    }

    @Override
    public String toString() {
        return "TrainingEvaluateRequest[messages=" + conversation.size()
                + ", actions=" + actions.size() + ", requestId=" + requestId + "]";
    }
}
