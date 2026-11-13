package com.wsid.ai;

import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;

/**
 * AI 서비스(FastAPI)를 부르는 유일한 통로.
 * 서비스 계층은 이 인터페이스만 알고, 구현은 설정(wsid.ai.mode)으로 바꿔 끼워요.
 */
public interface AiClient {

    /**
     * 훈련 한 턴의 제안을 받아요. AI는 제안만 하고, 다음 단계는 백엔드가 정해요.
     *
     * @throws AiUnavailableException 타임아웃, 오류, 형식이 깨진 응답
     */
    TrainingTurnSuggestion suggestTrainingTurn(TrainingTurnRequest request);

    com.wsid.ai.dto.DiscoveryResult suggestDiscovery(com.wsid.ai.dto.DiscoveryRequest request);
    // TODO(SOL-003): 피해 설명에서 송금·정보 제공·앱 설치·연락 지속 여부 추출
}
