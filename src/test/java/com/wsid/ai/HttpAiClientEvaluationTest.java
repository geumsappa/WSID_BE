package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

class HttpAiClientEvaluationTest {
    private static final String RESPONSE = """
            {"success":true,"data":{"overallFeedback":"훈련 기록을 돌아보세요.",
            "strengths":[],"improvements":[],"turnFeedback":[],
            "mockPageEvaluation":{"openedPage":true,"attemptedMockLogin":false,"checkedOfficialChannel":false},
            "recommendations":["공식 채널에서 확인해 보세요."]}}""";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(properties(), builder);
    private final TrainingEvaluateRequest request = new TrainingEvaluateRequest(new Scenario("훈련", "가상 상황"),
            List.of(new SimulationMessage("assistant", "어떻게 확인하시겠어요?"),
                    new SimulationMessage("user", "안내를 확인하겠습니다.")),
            List.of(new EvaluationAction("MOCK_PAGE_OPEN")), UUID.randomUUID());

    @Test
    void sendsContractHeadersAndJsonWithoutRequestIdAndUnwrapsData() {
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = client.evaluateTraining(request);
        assertEquals("훈련 기록을 돌아보세요.", result.overallFeedback());
        assertTrue(result.mockPageEvaluation().openedPage());
        assertFalse(result.mockPageEvaluation().attemptedMockLogin());
        assertFalse(result.mockPageEvaluation().checkedOfficialChannel());
        assertEquals(List.of("공식 채널에서 확인해 보세요."), result.recommendations());
        assertTrue(result.turnFeedback().isEmpty());
        server.verify();
    }

    @Test
    void additionalRawFieldsAreNotRetainedInTheTypedResult() {
        server.expect(anything()).andRespond(withSuccess("""
                {"success":true,"conversation":"private-conversation","data":{
                "overallFeedback":"평가","strengths":[],"improvements":[],"turnFeedback":[],"recommendations":[],
                "conversation":"private-conversation","rawInput":"private-input",
                "mockPageEvaluation":{"openedPage":false,"attemptedMockLogin":false,
                "checkedOfficialChannel":false,"evidenceText":"private-evidence"}}}""", MediaType.APPLICATION_JSON));
        String serialized = new ObjectMapper().writeValueAsString(client.evaluateTraining(request));
        assertFalse(serialized.contains("private"));
        assertFalse(serialized.contains("conversation"));
        assertFalse(serialized.contains("rawInput"));
        assertFalse(serialized.contains("evidenceText"));
        server.verify();
    }

    static Stream<String> invalidResponses() {
        var base = new ObjectMapper().readTree(RESPONSE);
        Stream<String> envelope = Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                "{\"success\":true,\"data\":[]}",
                RESPONSE.replace("\"success\":true", "\"success\":\"true\""),
                RESPONSE.replace("\"success\":true", "\"success\":1"),
                RESPONSE.replace("\"success\":true", "\"success\":null"),
                RESPONSE.replace("\"success\":true", "\"success\":false"),
                "{\"success\":false,\"error\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"private\"}}");
        Stream<String> fields = Stream.of("overallFeedback", "strengths", "improvements", "turnFeedback",
                "mockPageEvaluation", "recommendations").flatMap(field -> Stream.of(
                        missing(base, field), changed(base, field, null), changed(base, field, 123)));
        Stream<String> items = Stream.of("strengths", "improvements", "recommendations").flatMap(field ->
                Stream.of(changed(base, field, "wrong"), changed(base, field, List.of(1)),
                        changed(base, field, List.of(false)), changed(base, field, List.of(java.util.Map.of())),
                        changed(base, field, Arrays.asList((Object) null)), changed(base, field, List.of("")),
                        changed(base, field, List.of(" ")), changed(base, field, List.of("x".repeat(501))),
                        changed(base, field, Collections.nCopies(11, "項目"))));
        Stream<String> booleans = Stream.of("openedPage", "attemptedMockLogin", "checkedOfficialChannel")
                .flatMap(field -> Stream.of(missingPageFlag(base, field), changedPageFlag(base, field, null),
                        changedPageFlag(base, field, "false"), changedPageFlag(base, field, 0)));
        Stream<String> other = Stream.of(changed(base, "overallFeedback", ""), changed(base, "overallFeedback", " \n "),
                changed(base, "overallFeedback", "x".repeat(1001)), changed(base, "overallFeedback", List.of()),
                changed(base, "turnFeedback", List.of(java.util.Map.of("feedback", "内容"))),
                changed(base, "turnFeedback", Arrays.asList((Object) null)), changed(base, "turnFeedback", "wrong"),
                changed(base, "mockPageEvaluation", List.of()), changed(base, "mockPageEvaluation", "wrong"));
        return Stream.of(envelope, fields, items, booleans, other).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidTypesMissingFlagsAndUnsupportedTurnFeedback(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        server.verify();
    }

    @Test
    void acceptsAllStringListBoundariesAndFalseFlags() {
        var node = new ObjectMapper().readTree(RESPONSE);
        node = new ObjectMapper().readTree(changed(node, "overallFeedback", "x".repeat(1000)));
        for (String field : List.of("strengths", "improvements", "recommendations")) {
            node = new ObjectMapper().readTree(changed(node, field, Collections.nCopies(10, "x".repeat(500))));
        }
        node = new ObjectMapper().readTree(changedPageFlag(node, "openedPage", false));
        server.expect(anything()).andRespond(withSuccess(node.toString(), MediaType.APPLICATION_JSON));
        var result = client.evaluateTraining(request);
        assertEquals(1000, result.overallFeedback().length());
        assertEquals(10, result.strengths().size());
        assertEquals(10, result.improvements().size());
        assertEquals(10, result.recommendations().size());
        assertFalse(result.mockPageEvaluation().openedPage());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 429, 500, 503})
    void rejectsHttpErrorsEvenWithSuccessBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(RESPONSE));
        assertPrivateFailure();
        server.verify();
    }

    @Test
    void rejectsHtmlAndTimeoutWithoutResponseBodyOrCause() {
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        assertPrivateFailure(); assertPrivateFailure(); server.verify();
    }

    @Test
    void callerRetryReusesIdAndClientDoesNotRetryAutomatically() {
        expectRequest().andRespond(withServiceUnavailable());
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        assertNotNull(client.evaluateTraining(request));
        server.verify();
    }

    @Test
    void nullRequestDoesNotCallHttp() {
        assertThrows(IllegalArgumentException.class, () -> client.evaluateTraining(null));
        server.verify();
    }

    @Test
    void fourthBuilderOwnsEvaluationTransport() {
        var normal = RestClient.builder();
        var scenario = RestClient.builder();
        var evaluation = RestClient.builder();
        var normalServer = MockRestServiceServer.bindTo(normal).build();
        var scenarioServer = MockRestServiceServer.bindTo(scenario).build();
        var evaluationServer = MockRestServiceServer.bindTo(evaluation).build();
        var split = new HttpAiClient(properties(), normal, scenario, evaluation);
        evaluationServer.expect(requestTo("http://ai.test/internal/ai/training/evaluate"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertNotNull(split.evaluateTraining(request));
        normalServer.verify(); scenarioServer.verify(); evaluationServer.verify();
    }

    @Test
    void thirdArgumentConstructorKeepsEvaluationOnInjectedDefaultBuilder() {
        var normal = RestClient.builder();
        var scenario = RestClient.builder();
        var normalServer = MockRestServiceServer.bindTo(normal).build();
        var scenarioServer = MockRestServiceServer.bindTo(scenario).build();
        var compatible = new HttpAiClient(properties(), normal, scenario);
        normalServer.expect(anything()).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertNotNull(compatible.evaluateTraining(request));
        normalServer.verify(); scenarioServer.verify();
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://ai.test/internal/ai/training/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", request.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"scenario":{"title":"훈련","description":"가상 상황"},
                        "conversation":[{"role":"assistant","content":"어떻게 확인하시겠어요?"},
                        {"role":"user","content":"안내를 확인하겠습니다."}],
                        "actions":[{"actionCode":"MOCK_PAGE_OPEN"}]}""", JsonCompareMode.STRICT));
    }

    private void assertPrivateFailure() {
        var error = assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("훈련 기록을 돌아보세요"));
    }

    private static AiProperties properties() {
        return new AiProperties("http", "http://ai.test", "test-key", Duration.ofSeconds(2), Duration.ofSeconds(20));
    }

    private static String changed(tools.jackson.databind.JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.path("data")).set(field, new ObjectMapper().valueToTree(value));
        return root.toString();
    }

    private static String missing(tools.jackson.databind.JsonNode original, String field) {
        var root = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.path("data")).remove(field);
        return root.toString();
    }

    private static String changedPageFlag(tools.jackson.databind.JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.path("data").path("mockPageEvaluation"))
                .set(field, new ObjectMapper().valueToTree(value));
        return root.toString();
    }

    private static String missingPageFlag(tools.jackson.databind.JsonNode original, String field) {
        var root = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.path("data").path("mockPageEvaluation")).remove(field);
        return root.toString();
    }
}
