package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationReplyRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeAiClientSimulationTest {
    private final FakeAiClient client = new FakeAiClient();

    @Test
    void officialChannelKeywordProducesDeterministicSuggestion() {
        var request = request("공식 채널을 확인하겠습니다.");
        var result = client.replySimulation(request);
        assertEquals(result, client.replySimulation(request));
        assertEquals(List.of("OFFICIAL_CHANNEL_CHECK"), result.recognizedActions());
        assertFalse(result.shouldFinish());
        assertNull(result.mockPage());
    }

    @Test
    void unrelatedInputHasNoActionsAndIsNeverEchoed() {
        String input = "사용자만의 비공개 문장 https://private.invalid/";
        var result = client.replySimulation(request(input));
        assertTrue(result.recognizedActions().isEmpty());
        assertFalse(result.reply().contains(input));
        assertFalse(result.reply().contains("시나리오 비공개 설명"));
    }

    @Test
    void testMarkersControlFailureAndFinishSuggestionOnly() {
        assertThrows(AiUnavailableException.class, () -> client.replySimulation(request("[AI_FAIL]")));
        var result = client.replySimulation(request("[AI_FINISH]"));
        assertTrue(result.shouldFinish());
        assertTrue(result.recognizedActions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> client.replySimulation(null));
    }

    private SimulationReplyRequest request(String input) {
        return new SimulationReplyRequest(new Scenario("가상 훈련", "시나리오 비공개 설명"),
                List.of(), input, 1, UUID.randomUUID());
    }
}
