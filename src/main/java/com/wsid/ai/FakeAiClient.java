package com.wsid.ai;

import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.SimulationReplyResult;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import java.util.List;

/**
 * AI 서비스 없이 개발하고 테스트하기 위한 가짜 구현.
 * 항상 같은 입력에 같은 답을 돌려줘요.
 */
public class FakeAiClient implements AiClient {

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
