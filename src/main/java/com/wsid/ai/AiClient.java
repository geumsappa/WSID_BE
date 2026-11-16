package com.wsid.ai;

import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.SimulationReplyResult;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import com.wsid.ai.dto.TrainingEvaluateResult;
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

    /**
     * AI-04: 저장한 시나리오·이력으로 다음 역할극 대사와 행동 분석 제안을 받아요.
     * 종료·행동 매핑·DB 저장은 호출자가 결정해요. 기존 훈련 호출은 전환 전까지 유지해요.
     *
     * @throws AiUnavailableException 호출 실패 또는 계약에 어긋난 응답
     */
    SimulationReplyResult replySimulation(SimulationReplyRequest request);

    /**
     * AI-05: 종료된 훈련의 기록으로 리포트용 평가를 받아요.
     * 저장·근거 대조·기본 리포트 전환은 호출자의 책임이며 트랜잭션 밖에서 호출해요.
     *
     * @throws AiUnavailableException 호출 실패 또는 계약에 어긋난 응답
     */
    TrainingEvaluateResult evaluateTraining(TrainingEvaluateRequest request);

    /**
     * AI-06: 피해 설명에서 확인/미확정 사실을 구조화해요. 누적·수정·대응 카드 선택은 호출자 책임이에요.
     * 요청·응답 원문은 기록하지 않고 트랜잭션 밖에서 호출해요.
     *
     * @throws AiUnavailableException 호출 실패 또는 계약에 어긋난 응답
     */
    SolutionExtractResult extractSolutionFacts(SolutionExtractRequest request);
}
