package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TrainingEvaluateRequestTest {
    private final Scenario scenario = new Scenario("훈련", "가상 상황");
    private final SimulationMessage message = new SimulationMessage("user", "안내를 확인하겠습니다.");
    private final EvaluationAction action = new EvaluationAction("MOCK_PAGE_OPEN");

    @Test
    void acceptsConversationOnlyOrActionOnlyButRejectsNoEvidence() {
        assertDoesNotThrow(() -> request(List.of(message), List.of()));
        assertDoesNotThrow(() -> request(List.of(), List.of(action)));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), List.of()));
    }

    @Test
    void fullEvaluationAllowsFortyMessagesAndOneHundredActions() {
        var longMessage = new SimulationMessage("assistant", "x".repeat(1000));
        var longCode = new EvaluationAction("A".repeat(100));
        assertDoesNotThrow(() -> request(Collections.nCopies(40, longMessage), Collections.nCopies(100, longCode)));
        assertThrows(IllegalArgumentException.class,
                () -> request(Collections.nCopies(41, message), List.of(action)));
        assertThrows(IllegalArgumentException.class,
                () -> request(List.of(message), Collections.nCopies(101, action)));
    }

    @Test
    void rejectsMissingFieldsAndNullElementsWithInputException() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateRequest(null, List.of(message), List.of(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingEvaluateRequest(scenario, List.of(message), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> request(null, List.of(action)));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(message), null));
        assertThrows(IllegalArgumentException.class,
                () -> request(Arrays.asList((SimulationMessage) null), List.of(action)));
        assertThrows(IllegalArgumentException.class,
                () -> request(List.of(message), Arrays.asList((EvaluationAction) null)));
    }

    static Stream<String> invalidCodes() {
        return Stream.of(null, "", " ", "mock_page_open", "MOCK PAGE OPEN", "MOCK-PAGE-OPEN", "가나다", "A".repeat(101));
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    void rejectsInvalidActionCode(String code) {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationAction(code));
    }

    @Test
    void preservesFullOrderDuplicatesAndUnknownCodesAndCopiesLists() {
        var messages = new ArrayList<>(List.of(new SimulationMessage("assistant", "먼저 확인해 주세요."), message));
        var actions = new ArrayList<>(List.of(action, new EvaluationAction("SOMETHING_NEW"), action));
        var request = request(messages, actions);
        messages.clear();
        actions.clear();
        assertEquals(List.of("assistant", "user"), request.conversation().stream().map(SimulationMessage::role).toList());
        assertEquals(List.of("MOCK_PAGE_OPEN", "SOMETHING_NEW", "MOCK_PAGE_OPEN"),
                request.actions().stream().map(EvaluationAction::actionCode).toList());
        assertThrows(UnsupportedOperationException.class, () -> request.conversation().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.actions().clear());
    }

    @Test
    void doesNotExposeEvidenceOrScenarioInToStringOrErrors() {
        var privateAction = new EvaluationAction("PRIVATE_ACTION");
        var request = new TrainingEvaluateRequest(new Scenario("비공개 제목", "비공개 설명"),
                List.of(new SimulationMessage("user", "사용자 비공개 원문")), List.of(privateAction), UUID.randomUUID());
        for (String secret : List.of("비공개 제목", "비공개 설명", "사용자 비공개 원문", "PRIVATE_ACTION")) {
            assertFalse(request.toString().contains(secret));
        }
        assertFalse(privateAction.toString().contains(privateAction.actionCode()));
        var error = assertThrows(IllegalArgumentException.class, () -> new EvaluationAction("private action"));
        assertFalse(error.getMessage().contains("private action"));
    }

    private TrainingEvaluateRequest request(List<SimulationMessage> messages, List<EvaluationAction> actions) {
        return new TrainingEvaluateRequest(scenario, messages, actions, UUID.randomUUID());
    }
}
