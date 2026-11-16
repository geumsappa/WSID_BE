package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeAiClientEvaluationTest {
    private final FakeAiClient client = new FakeAiClient();

    @Test
    void explicitMockPageOpenProducesDeterministicPageFlag() {
        var request = request(List.of(), List.of(new EvaluationAction("MOCK_PAGE_OPEN")));
        var result = client.evaluateTraining(request);
        assertEquals(result, client.evaluateTraining(request));
        assertTrue(result.mockPageEvaluation().openedPage());
        assertFalse(result.mockPageEvaluation().attemptedMockLogin());
        assertFalse(result.mockPageEvaluation().checkedOfficialChannel());
        assertTrue(result.turnFeedback().isEmpty());
    }

    @Test
    void conversationAndUnknownCodesDoNotInventActualEventsOrEchoEvidence() {
        String input = "사용자 비공개 원문: 공식 채널을 확인하겠습니다.";
        var result = client.evaluateTraining(request(List.of(new SimulationMessage("user", input)),
                List.of(new EvaluationAction("SOMETHING_NEW"), new EvaluationAction("OFFICIAL_CHANNEL_CHECK"))));
        assertFalse(result.mockPageEvaluation().openedPage());
        assertFalse(result.mockPageEvaluation().checkedOfficialChannel());
        assertFalse(result.overallFeedback().contains(input));
        assertFalse(result.overallFeedback().contains("비공개 시나리오"));
        assertTrue(result.strengths().isEmpty());
    }

    @Test
    void supportsConversationOnlyAndFailMarkerWithoutLeakingCause() {
        assertNotNull(client.evaluateTraining(request(List.of(new SimulationMessage("user", "연습을 마쳤어요.")), List.of())));
        var error = assertThrows(AiUnavailableException.class,
                () -> client.evaluateTraining(request(List.of(new SimulationMessage("user", "비공개 [AI_FAIL]")), List.of())));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("비공개"));
        assertThrows(IllegalArgumentException.class, () -> client.evaluateTraining(null));
    }

    private TrainingEvaluateRequest request(List<SimulationMessage> messages, List<EvaluationAction> actions) {
        return new TrainingEvaluateRequest(new Scenario("훈련", "비공개 시나리오"), messages, actions, UUID.randomUUID());
    }
}
