package com.wsid.ai;

import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.SimulationReplyResult;
import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistAnswerResult;
import com.wsid.ai.dto.SolutionAssistClarification;
import com.wsid.ai.dto.SolutionAssistIntent;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistPlanResult;
import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.SolutionCandidateReference;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.SolutionExplainResult;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import com.wsid.ai.dto.TrainingEvaluateResult;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 서비스 없이 개발하고 테스트하기 위한 가짜 구현.
 * 항상 같은 입력에 같은 답을 돌려줘요.
 */
public class FakeAiClient implements AiClient {

    static final String FAKE_ASSIST_REFERENCE = "FAKE_ASSIST_GUIDE";
    private static final List<String> ASSIST_FACT_ORDER =
            List.of("transferredMoney", "sharedInformation", "installedApp", "contactContinues");
    private static final Map<String, String> ASSIST_QUESTIONS = Map.of(
            "transferredMoney", "상대방에게 돈을 보낸 적이 있나요?",
            "sharedInformation", "상대방에게 개인정보나 인증번호를 알려준 적이 있나요?",
            "installedApp", "상대방이 알려준 앱을 설치한 적이 있나요?",
            "contactContinues", "상대방이 지금도 계속 연락하고 있나요?");

    /**
     * 판단 품질을 대신하지 않는 고정 PLAN 결과예요. 질문 문장으로 의도를 추측하지 않고 첫 실행은 GENERAL로 둬요.
     * 표식: "[AI_FAIL]"은 실패, "[OUT_OF_SCOPE]"는 답변 불가. 그 외에는 아직 묻지 않은 UNKNOWN 사실을 고정 순서로
     * 하나씩 묻고, 물을 것이 없거나 횟수를 다 쓰면 근거 후보를 돌려줘요.
     */
    @Override
    public SolutionAssistPlanResult planSolutionAssist(SolutionAssistPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist PLAN 요청이 필요해요.");
        }
        String question = request.question() == null ? "" : request.question();
        if (question.contains("[AI_FAIL]")) {
            throw new AiUnavailableException("가짜 상담 assist PLAN 실패");
        }
        var intent = request.questionIntent() == null ? SolutionAssistIntent.GENERAL : request.questionIntent();
        SolutionAssistPlanResult result;
        if (question.contains("[OUT_OF_SCOPE]")) {
            result = new SolutionAssistPlanResult(SolutionAssistStatus.UNABLE_TO_ANSWER, null, null, List.of(),
                    SolutionAssistReason.OUT_OF_SCOPE);
        } else {
            String factKey = request.remainingClarifications() == 0 ? null : ASSIST_FACT_ORDER.stream()
                    .filter(key -> "UNKNOWN".equals(request.facts().get(key)))
                    .filter(key -> !request.askedFactKeys().contains(key)).findFirst().orElse(null);
            result = factKey != null
                    ? new SolutionAssistPlanResult(SolutionAssistStatus.NEED_USER_INPUT, intent,
                            new SolutionAssistClarification(factKey, ASSIST_QUESTIONS.get(factKey),
                                    SolutionAssistClarification.OPTIONS), List.of(), null)
                    : new SolutionAssistPlanResult(SolutionAssistStatus.EVIDENCE_READY, intent, null,
                            List.of(new SolutionCandidateReference(FAKE_ASSIST_REFERENCE, "1")), null);
        }
        return SolutionAssistValidator.validate(request, result);
    }

    /** 고정 ANSWER 결과예요. 첫 근거만 인용하고, 근거 제목에 "[AI_FAIL]"이 있으면 실패해요. */
    @Override
    public SolutionAssistAnswerResult answerSolutionAssist(SolutionAssistAnswerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist ANSWER 요청이 필요해요.");
        }
        if (request.verifiedReferences().stream().anyMatch(reference -> reference.title().contains("[AI_FAIL]"))) {
            throw new AiUnavailableException("가짜 상담 assist ANSWER 실패");
        }
        var result = new SolutionAssistAnswerResult(SolutionAssistStatus.ANSWERED,
                "검토된 안내의 순서대로 확인하고, 화면의 대응 카드를 먼저 따라 주세요.",
                List.of(request.verifiedReferences().getFirst().code()), null);
        return SolutionAssistValidator.validate(request, result);
    }

    /** 검색·설명 품질을 대신하지 않는 고정 개발용 결과예요. 실패 표식은 근거가 있을 때만 사용해요. */
    @Override
    public SolutionExplainResult explainSolution(SolutionExplainRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("대응 설명 요청이 필요해요.");
        }
        if (request.verifiedReferences().stream().anyMatch(reference -> reference.title().contains("[AI_FAIL]"))) {
            throw new AiUnavailableException("가짜 AI-07 실패");
        }
        var result = request.verifiedReferences().isEmpty()
                ? new SolutionExplainResult("확인된 근거가 없어 추가 설명을 제공하지 않습니다.", List.of())
                : new SolutionExplainResult("검토된 대응 카드의 순서와 준비 항목을 함께 확인해 주세요.",
                        List.of(request.verifiedReferences().getFirst().code()));
        return SolutionExplanationValidator.validate(request, result);
    }

    /** 합성 문장 몇 개만 인식하는 개발용 결과. 일반 문장의 키워드로 사실을 추측하지 않아요. */
    @Override
    public SolutionExtractResult extractSolutionFacts(SolutionExtractRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("피해 사실 추출 요청이 필요해요.");
        }
        String description = request.description();
        if (description.contains("[AI_FAIL]")) {
            throw new AiUnavailableException("가짜 AI-06 실패");
        }
        Map<String, String> facts = new LinkedHashMap<>();
        List<String> keys = List.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp");
        keys.forEach(key -> facts.put(key, request.knownFacts().getOrDefault(key, "UNKNOWN")));
        switch (description.strip()) {
            case "송금 후 연락이 끊겼어요." -> {
                facts.put("transferredMoney", "YES");
                facts.put("contactContinues", "NO");
            }
            case "송금하지 않았어요.", "송금하려다 멈췄어요." -> facts.put("transferredMoney", "NO");
            case "개인정보를 전달했어요." -> facts.put("sharedInformation", "YES");
            case "개인정보를 전달하지 않았어요." -> facts.put("sharedInformation", "NO");
            case "앱을 설치했어요." -> facts.put("installedApp", "YES");
            case "앱을 설치하지 않았어요." -> facts.put("installedApp", "NO");
            case "상대방과 계속 연락하고 있어요." -> facts.put("contactContinues", "YES");
            default -> { /* 명시적인 개발용 문장 외에는 기존 사실/UNKNOWN을 유지해요. */ }
        }
        List<String> uncertain = keys.stream().filter(key -> "UNKNOWN".equals(facts.get(key))).toList();
        return new SolutionExtractResult(facts, uncertain);
    }

    /** 평가 연결 전 개발용 결과. 사용자 원문을 평가 문구에 끼워 넣지 않아요. */
    @Override
    public TrainingEvaluateResult evaluateTraining(TrainingEvaluateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("훈련 평가 요청이 필요해요.");
        }
        if (request.conversation().stream().anyMatch(message -> message.content().contains("[AI_FAIL]"))) {
            throw new AiUnavailableException("가짜 AI-05 실패");
        }
        boolean openedPage = request.actions().stream()
                .anyMatch(action -> "MOCK_PAGE_OPEN".equals(action.actionCode()));
        return new TrainingEvaluateResult(
                "훈련 기록을 바탕으로 확인 방법을 돌아보세요.",
                List.of(), List.of(), List.of(),
                new TrainingEvaluateResult.MockPageEvaluation(openedPage, false, false),
                List.of("공식 채널에서 다시 확인해 보세요."));
    }

    /** 개발용 키워드 분류. 실제 분석·가드레일을 검증하는 구현은 아니에요. */
    @Override
    public SimulationReplyResult replySimulation(SimulationReplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("역할극 응답 요청이 필요해요.");
        }
        String input = request.userInput();
        if (input.contains("[AI_FAIL]")) {
            throw new AiUnavailableException("가짜 AI-04 실패");
        }
        List<String> actions = input.contains("공식") && input.contains("확인")
                ? List.of("OFFICIAL_CHANNEL_CHECK") : List.of();
        return new SimulationReplyResult(
                "알겠습니다. 확인하신 뒤에 말씀해 주세요.", actions, input.contains("[AI_FINISH]"), null);
    }

    /** 개발 전용. 사용자 원문을 재출력하지 않고 알려진 역할만 반영해요. */
    @Override
    public ScenarioGenerateResult generateScenario(ScenarioGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("시나리오 생성 요청이 필요해요.");
        }
        String role = request.facts().getOrDefault("role", "");
        String description = switch (role) {
            case "판매자" -> "중고 물품을 판매하는 가상 상황에서 상대의 안내를 확인하고 안전한 대응을 연습해요.";
            case "구매자" -> "중고 물품을 구매하는 가상 상황에서 거래 안내를 확인하고 안전한 대응을 연습해요.";
            default -> "중고거래의 가상 상황에서 안내를 확인하고 안전한 대응을 연습해요.";
        };
        return new ScenarioGenerateResult(
                new ScenarioGenerateResult.Scenario("중고거래 안전 확인 훈련", description), "BANK_NOTICE");
    }

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
    /** 개발용 결정적 응답. [AI_FAIL]은 실패 흐름 검증에만 사용해요. */
    @Override
    public com.wsid.ai.dto.DiscoveryResult suggestDiscovery(com.wsid.ai.dto.DiscoveryRequest request) {
        if (request.currentInput().contains("[AI_FAIL]")) throw new AiUnavailableException("가짜 AI 실패");
        java.util.Map<String, Object> facts = new java.util.LinkedHashMap<>(request.knownFacts());
        java.util.List<String> inputs = new java.util.ArrayList<>();
        request.conversation().stream().filter(t -> "user".equals(t.role())).forEach(t -> inputs.add(t.content()));
        inputs.add(request.currentInput());
        for (String text : inputs) {
            if (text.contains("중고거래")) facts.put("activity", "중고거래");
            if (text.contains("판매") || text.contains("팔았") || text.contains("파는")) facts.put("role", "판매자");
            if (text.contains("구매") || text.contains("샀") || text.contains("사는")) facts.put("role", "구매자");
            if (text.contains("문자")) facts.put("channel", "문자");
            if (text.contains("전화")) facts.put("channel", "전화");
            if (text.contains("채팅")) facts.put("channel", "앱 채팅");
            if (text.contains("노트북")) facts.put("item", "노트북");
        }
        String question = !facts.containsKey("activity") ? "최근에 어떤 일을 하셨나요?"
                : !facts.containsKey("role") ? "물건을 사는 쪽이었나요, 파는 쪽이었나요?"
                : !facts.containsKey("channel") ? "상대방과 어떻게 연락하셨나요?" : "그때 기억에 남는 일이 있나요?";
        return new com.wsid.ai.dto.DiscoveryResult(facts, question, facts.containsKey("activity") && facts.containsKey("role"));
    }

    @Override
    public OcrExtractResult extractText(List<OcrImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("이미지가 한 장 이상 필요해요.");
        }
        // 화면에서 OCR 실패 흐름을 확인해 볼 수 있도록 둔 규칙 하나
        boolean failRequested = images.stream()
                .anyMatch(image -> image.filename() != null && image.filename().contains("ocr-fail"));
        if (failRequested) {
            return OcrExtractResult.failed();
        }
        return new OcrExtractResult("(가짜 OCR) 이미지 " + images.size() + "장에서 읽은 거래 관련 안내", true);
    }
}
