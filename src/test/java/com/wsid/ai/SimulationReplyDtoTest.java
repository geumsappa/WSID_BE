package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.SimulationReplyResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SimulationReplyDtoTest {
    private final Scenario scenario = new Scenario("가상 훈련", "가상 상황 설명");

    @Test
    void acceptsBoundariesAndCopiesHistoryWithoutRecountingTurns() {
        var message = new SimulationMessage("assistant", "x".repeat(1000));
        var history = new ArrayList<>(Collections.nCopies(20, message));
        var request = request(history, "y".repeat(1000), 12, UUID.randomUUID());
        history.clear();
        assertEquals(20, request.messages().size());
        assertEquals(12, request.turnCount());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
        assertDoesNotThrow(() -> request(List.of(), "현재 입력", 3, UUID.randomUUID()));
    }

    @Test
    void preservesContentAndHistoryOrder() {
        var history = List.of(new SimulationMessage("assistant", " 이전 대사 "),
                new SimulationMessage("user", "이전 답변"));
        var request = request(history, " 현재 입력 ", 2, UUID.randomUUID());
        assertEquals(history, request.messages());
        assertEquals(" 이전 대사 ", request.messages().getFirst().content());
        assertEquals(" 현재 입력 ", request.userInput());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"system", "USER", "AI", " assistant ", "unknown"})
    void rejectsInvalidRole(String role) {
        assertThrows(IllegalArgumentException.class, () -> new SimulationMessage(role, "내용"));
    }

    static Stream<String> invalidTexts() {
        return Stream.of(null, "", " \n\t ", "x".repeat(1001));
    }

    @ParameterizedTest
    @MethodSource("invalidTexts")
    void rejectsInvalidMessageInputAndReply(String text) {
        assertThrows(IllegalArgumentException.class, () -> new SimulationMessage("user", text));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), text, 1, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationReplyResult(text, List.of(), false, null));
    }

    @Test
    void rejectsMissingScenarioHistoryIdAndOversizedHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationReplyRequest(null, List.of(), "입력", 1, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> request(null, "입력", 1, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> request(Arrays.asList((SimulationMessage) null), "입력", 1, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> request(Collections.nCopies(21, new SimulationMessage("assistant", "대사")), "입력", 1, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), "입력", 1, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveTurnCount(int turnCount) {
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), "입력", turnCount, UUID.randomUUID()));
    }

    static Stream<List<String>> invalidActions() {
        return Stream.of(null, Arrays.asList((String) null), List.of(""), List.of("OFFICIAL CHECK"),
                List.of("official_channel_check"), List.of("가나다"), List.of("A".repeat(101)),
                Collections.nCopies(11, "OFFICIAL_CHANNEL_CHECK"));
    }

    @ParameterizedTest
    @MethodSource("invalidActions")
    void rejectsInvalidActionShapes(List<String> actions) {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationReplyResult("대사", actions, false, null));
    }

    @Test
    void retainsUnknownAndDuplicateCodesAndCopiesResultList() {
        var actions = new ArrayList<>(List.of("OFFICIAL_CHANNEL_CHECK", "SOMETHING_NEW", "OFFICIAL_CHANNEL_CHECK"));
        var result = new SimulationReplyResult("대사", actions, true, null);
        actions.clear();
        assertEquals(List.of("OFFICIAL_CHANNEL_CHECK", "SOMETHING_NEW", "OFFICIAL_CHANNEL_CHECK"), result.recognizedActions());
        assertTrue(result.shouldFinish());
        assertNull(result.mockPage());
        assertThrows(UnsupportedOperationException.class, () -> result.recognizedActions().clear());
        assertDoesNotThrow(() -> new SimulationReplyResult("x".repeat(1000),
                Collections.nCopies(10, "A".repeat(100)), false, null));
    }

    @Test
    void toStringDoesNotExposeScenarioMessageInputReplyOrActionValues() {
        var privateScenario = new Scenario("시나리오 제목 원문", "시나리오 설명 원문");
        var message = new SimulationMessage("assistant", "지난 대사 원문");
        var request = new SimulationReplyRequest(privateScenario, List.of(message), "현재 입력 원문", 1, UUID.randomUUID());
        var result = new SimulationReplyResult("다음 대사 원문", List.of("PRIVATE_ACTION"), false, null);
        for (String secret : List.of(privateScenario.title(), privateScenario.description(), message.content(), request.userInput())) {
            assertFalse(request.toString().contains(secret));
        }
        assertFalse(message.toString().contains(message.content()));
        assertFalse(result.toString().contains(result.reply()));
        assertFalse(result.toString().contains("PRIVATE_ACTION"));
    }

    private SimulationReplyRequest request(List<SimulationMessage> messages, String input, int turns, UUID id) {
        return new SimulationReplyRequest(scenario, messages, input, turns, id);
    }
}
