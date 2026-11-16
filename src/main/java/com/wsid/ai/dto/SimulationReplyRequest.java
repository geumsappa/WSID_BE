package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

/** AI-04 요청. 훈련 엔티티 대신 시나리오 값과 선택한 이력만 전달해요. */
public record SimulationReplyRequest(
        ScenarioGenerateResult.Scenario scenario,
        List<SimulationMessage> messages,
        String userInput,
        int turnCount,
        @JsonIgnore UUID requestId) {

    public static final int MAX_MESSAGES = 20;
    public static final int MAX_INPUT_LENGTH = 1_000;

    public SimulationReplyRequest {
        if (scenario == null) {
            throw new IllegalArgumentException("훈련 시나리오가 필요해요.");
        }
        if (messages == null || messages.size() > MAX_MESSAGES
                || messages.stream().anyMatch(message -> message == null)) {
            throw new IllegalArgumentException("역할극 이력 목록이 올바르지 않아요.");
        }
        if (userInput == null || userInput.isBlank() || userInput.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("현재 사용자 입력이 올바르지 않아요.");
        }
        if (turnCount < 1 || requestId == null) {
            throw new IllegalArgumentException("훈련 턴 수나 요청 ID가 올바르지 않아요.");
        }
        messages = List.copyOf(messages);
    }

    @Override
    public String toString() {
        return "SimulationReplyRequest[messageCount=" + messages.size()
                + ", inputLength=" + userInput.length() + ", turnCount=" + turnCount
                + ", requestId=" + requestId + "]";
    }
}
