package com.wsid.ai.dto;

/**
 * 훈련 한 턴에 AI 서비스로 보내는 맥락. AI 서비스는 상태를 저장하지 않으니 필요한 건 매번 다 보내요.
 * 필드 이름은 AI repo와 맞춘 API 계약이에요. 바꿀 때는 양쪽을 같이 고쳐요.
 */
public record TrainingTurnRequest(
        String fraudTypeCode,
        String phase,
        int simulationTurnCount,
        boolean keySceneShown,
        String userMessage) {
    // TODO(SIM-007): 최근 대화, 확인된 사실(context_facts), 시나리오(scenario_json)를 추가해요.
}
