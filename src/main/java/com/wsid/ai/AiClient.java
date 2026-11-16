package com.wsid.ai;

import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import java.util.List;

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

    /**
     * AI-02: 훈련 이미지에서 문자를 읽고 민감정보를 검사해요.
     * AI가 OCR_FAILED로 답하면 예외 대신 {@link OcrExtractResult#failed()}를 돌려줘요.
     *
     * @param images 한 장 이상. 원본은 이 호출 동안만 메모리에 두고 보관하지 않아요.
     * @throws AiUnavailableException 타임아웃, 오류, 형식이 깨진 응답
     */
    OcrExtractResult extractText(List<OcrImage> images);

    /**
     * AI-03: 확인한 사실로 가상 훈련의 개요와 모의 페이지 코드 제안을 받아요.
     * 호출자는 트랜잭션 밖에서 호출하고, 저장 전 콘텐츠·템플릿 적합성을 확인해야 해요.
     *
     * @throws AiUnavailableException 호출 실패 또는 계약에 어긋난 응답
     */
    ScenarioGenerateResult generateScenario(ScenarioGenerateRequest request);

    // TODO(SOL-003): 피해 설명에서 송금·정보 제공·앱 설치·연락 지속 여부 추출
}
