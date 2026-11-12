package com.wsid.ai;

import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;

/**
 * AI 서비스 없이 개발하고 테스트하기 위한 가짜 구현.
 * 항상 같은 입력에 같은 답을 돌려줘요.
 */
public class FakeAiClient implements AiClient {

    @Override
    public TrainingTurnSuggestion suggestTrainingTurn(TrainingTurnRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage();

        // 화면에서 조기 종료를 확인해 볼 수 있도록 둔 규칙 하나
        if (message.contains("연락을 끊")) {
            return new TrainingTurnSuggestion("END_CONTACT_AND_VERIFY", "(가짜 응답) 아, 네...", false, true, false);
        }
        return new TrainingTurnSuggestion(
                "ASK_QUESTION",
                "(가짜 응답) " + (request.simulationTurnCount() + 1) + "번째 대사예요.",
                false,
                true,
                false);
    }
}
