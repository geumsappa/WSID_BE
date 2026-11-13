package com.wsid.ai;

import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import java.util.List;

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
