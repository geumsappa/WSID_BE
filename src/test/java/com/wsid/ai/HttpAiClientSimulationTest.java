package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.TrainingTurnRequest;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class HttpAiClientSimulationTest {
    private static final String RESPONSE = """
            {"success":true,"data":{"reply":"공식 고객센터로 확인해 보세요.",
            "recognizedActions":["OFFICIAL_CHANNEL_CHECK"],"shouldFinish":false,"mockPage":null}}""";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(new AiProperties("http", "http://ai.test", "test-key",
            Duration.ofSeconds(2), Duration.ofSeconds(20)), builder);
    private final SimulationReplyRequest request = new SimulationReplyRequest(new Scenario("훈련 제목", "가상 상황"),
            List.of(new SimulationMessage("assistant", "첫 대사"), new SimulationMessage("user", "지난 답변"),
                    new SimulationMessage("assistant", "다음 대사")),
            "공식 채널을 확인하겠습니다.", 2, UUID.randomUUID());

    @Test
    void sendsNewContractAndHeadersWithoutRequestIdInBody() {
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = client.replySimulation(request);
        assertEquals("공식 고객센터로 확인해 보세요.", result.reply());
        assertEquals(List.of("OFFICIAL_CHANNEL_CHECK"), result.recognizedActions());
        assertFalse(result.shouldFinish());
        assertNull(result.mockPage());
        server.verify();
    }

    @Test
    void preservesUnknownAndDuplicateActionsAndFinishSuggestionWithoutDecidingState() {
        server.expect(anything()).andRespond(withSuccess(data("대사", List.of(
                "OFFICIAL_CHANNEL_CHECK", "SOMETHING_NEW", "OFFICIAL_CHANNEL_CHECK"), true), MediaType.APPLICATION_JSON));
        var result = client.replySimulation(request);
        assertEquals(List.of("OFFICIAL_CHANNEL_CHECK", "SOMETHING_NEW", "OFFICIAL_CHANNEL_CHECK"), result.recognizedActions());
        assertTrue(result.shouldFinish());
        server.verify();
    }

    @Test
    void permitsEmptyActionsAndAdditionalFields() {
        server.expect(anything()).andRespond(withSuccess("""
                {"success":true,"extra":1,"data":{"reply":"대사","recognizedActions":[],
                "shouldFinish":false,"mockPage":null,"extra":{}}}""", MediaType.APPLICATION_JSON));
        assertTrue(client.replySimulation(request).recognizedActions().isEmpty());
        server.verify();
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                "{\"success\":true,\"data\":[]}",
                RESPONSE.replace("\"success\":true", "\"success\":\"true\""),
                RESPONSE.replace("\"success\":true", "\"success\":1"),
                RESPONSE.replace("\"success\":true", "\"success\":null"),
                RESPONSE.replace("\"success\":true", "\"success\":false"),
                RESPONSE.replace("\"shouldFinish\":false", "\"shouldFinish\":\"false\""),
                RESPONSE.replace("\"shouldFinish\":false", "\"shouldFinish\":0"),
                RESPONSE.replace("\"shouldFinish\":false", "\"shouldFinish\":null"),
                RESPONSE.replace("\"shouldFinish\":false,", ""),
                RESPONSE.replace(",\"mockPage\":null", ""),
                RESPONSE.replace("\"mockPage\":null", "\"mockPage\":{}"),
                RESPONSE.replace("\"mockPage\":null", "\"mockPage\":[]"),
                RESPONSE.replace("\"mockPage\":null", "\"mockPage\":\"BANK_NOTICE\""),
                RESPONSE.replace("\"mockPage\":null", "\"mockPage\":false"),
                RESPONSE.replace("\"recognizedActions\":[\"OFFICIAL_CHANNEL_CHECK\"]", "\"recognizedActions\":null"),
                RESPONSE.replace("\"recognizedActions\":[\"OFFICIAL_CHANNEL_CHECK\"],", ""),
                data(123, List.of(), false), data(null, List.of(), false),
                data("", List.of(), false), data(" \n ", List.of(), false),
                data("x".repeat(1001), List.of(), false), data("대사", "OFFICIAL_CHANNEL_CHECK", false),
                data("대사", List.of(123), false), data("대사", List.of(true), false),
                data("대사", Collections.singletonList(null), false), data("대사", List.of(Map.of()), false),
                data("대사", List.of(List.of()), false), data("대사", List.of(""), false),
                data("대사", List.of("official_check"), false), data("대사", List.of("OFFICIAL CHECK"), false),
                data("대사", List.of("A".repeat(101)), false),
                data("대사", Collections.nCopies(11, "OFFICIAL_CHANNEL_CHECK"), false),
                "{\"success\":false,\"error\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"private\"}}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidJsonTypesAndContractValues(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        server.verify();
    }

    @Test
    void acceptsResponseLengthBoundaries() {
        server.expect(anything()).andRespond(withSuccess(data("x".repeat(1000),
                Collections.nCopies(10, "A".repeat(100)), false), MediaType.APPLICATION_JSON));
        var result = client.replySimulation(request);
        assertEquals(1000, result.reply().length());
        assertEquals(10, result.recognizedActions().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 429, 500, 503})
    void rejectsHttpErrorsEvenWithSuccessBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(RESPONSE));
        assertPrivateFailure();
        server.verify();
    }

    @Test
    void rejectsHtmlAndTransportFailuresWithoutRetainingOriginalException() {
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        assertPrivateFailure();
        assertPrivateFailure();
        server.verify();
    }

    @Test
    void doesNotRetryAutomaticallyAndReusesIdWhenCallerRetries() {
        expectRequest().andRespond(withServiceUnavailable());
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        assertNotNull(client.replySimulation(request));
        server.verify();
    }

    @Test
    void legacyTrainingCallKeepsItsPathBodyAndUnwrappedResponse() {
        server.expect(requestTo("http://ai.test/internal/training/turn"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(content().json("""
                        {"fraudTypeCode":"USED_GOODS_TRADE","phase":"SIMULATION","simulationTurnCount":2,
                        "keySceneShown":false,"userMessage":"질문"}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"userAction":"ASK_QUESTION","reply":"기존 대사","suggestKeyScene":false,
                        "guardrailPassed":true,"realIncident":false}""", MediaType.APPLICATION_JSON));
        var result = client.suggestTrainingTurn(new TrainingTurnRequest("USED_GOODS_TRADE", "SIMULATION", 2, false, "질문"));
        assertTrue(result.replyUsable());
        assertEquals("기존 대사", result.reply());
        server.verify();
    }

    @Test
    void nullRequestIsLocalInputErrorAndDoesNotCallHttp() {
        assertThrows(IllegalArgumentException.class, () -> client.replySimulation(null));
        server.verify();
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://ai.test/internal/ai/simulation/reply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", request.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"scenario":{"title":"훈련 제목","description":"가상 상황"},
                        "messages":[{"role":"assistant","content":"첫 대사"},{"role":"user","content":"지난 답변"},
                        {"role":"assistant","content":"다음 대사"}],
                        "userInput":"공식 채널을 확인하겠습니다.","turnCount":2}""", JsonCompareMode.STRICT));
    }

    private void assertPrivateFailure() {
        var error = assertThrows(AiUnavailableException.class, () -> client.replySimulation(request));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("공식 고객센터"));
    }

    private static String data(Object reply, Object actions, Object finish) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reply", reply);
        payload.put("recognizedActions", actions);
        payload.put("shouldFinish", finish);
        payload.put("mockPage", null);
        return new ObjectMapper().writeValueAsString(Map.of("success", true, "data", payload));
    }
}
