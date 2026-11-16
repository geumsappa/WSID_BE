package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.SolutionExtractRequest;
import java.net.SocketTimeoutException;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class HttpAiClientSolutionExtractTest {
    private static final String RESPONSE = """
            {"success":true,"data":{"facts":{"transferredMoney":"YES","contactContinues":"NO",
            "sharedInformation":"UNKNOWN","installedApp":"UNKNOWN"},
            "uncertainFields":["sharedInformation","installedApp"]}}""";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(new AiProperties("http", "http://ai.test", "test-key",
            Duration.ofSeconds(2), Duration.ofSeconds(20)), builder);
    private final SolutionExtractRequest request = new SolutionExtractRequest("송금 후 연락이 끊겼어요.", Map.of(), UUID.randomUUID());

    @Test
    void sendsContractAndHeadersWithoutRequestIdInBody() {
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = client.extractSolutionFacts(request);
        assertEquals("YES", result.facts().get("transferredMoney"));
        assertEquals("NO", result.facts().get("contactContinues"));
        assertEquals(List.of("sharedInformation", "installedApp"), result.uncertainFields());
        server.verify();
    }

    @Test
    void sendsKnownFactsAndDoesNotMutateOrMergeThemInsideAdapter() {
        var requestWithFacts = new SolutionExtractRequest("앱을 설치했어요.", Map.of("transferredMoney", "YES"), UUID.randomUUID());
        server.expect(anything()).andExpect(content().json("""
                {"description":"앱을 설치했어요.","knownFacts":{"transferredMoney":"YES"}}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertNotNull(client.extractSolutionFacts(requestWithFacts));
        assertEquals(Map.of("transferredMoney", "YES"), requestWithFacts.knownFacts());
        server.verify();
    }

    @Test
    void ignoresOuterEvidenceFieldsRatherThanRetainingPrivateText() {
        server.expect(anything()).andRespond(withSuccess(RESPONSE.replace("\"data\":{",
                "\"rawDescription\":\"private-description\",\"data\":{\"evidenceText\":\"private-evidence\","), MediaType.APPLICATION_JSON));
        String serialized = new ObjectMapper().writeValueAsString(client.extractSolutionFacts(request));
        assertFalse(serialized.contains("private"));
        assertFalse(serialized.contains("evidenceText"));
        assertFalse(serialized.contains("description"));
    }

    static Stream<String> invalidBodies() {
        var base = new ObjectMapper().readTree(RESPONSE);
        Stream<String> envelope = Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                "{\"success\":true,\"data\":[]}",
                RESPONSE.replace("\"success\":true", "\"success\":\"true\""),
                RESPONSE.replace("\"success\":true", "\"success\":1"),
                RESPONSE.replace("\"success\":true", "\"success\":false"),
                "{\"success\":false,\"error\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"private\"}}");
        Stream<String> types = Stream.of("facts", "uncertainFields").flatMap(field -> Stream.of(
                changed(base, field, null), changed(base, field, "wrong"), changed(base, field, 1), missing(base, field)));
        Stream<String> factValues = SolutionExtractRequest.FACT_KEYS.stream().flatMap(key -> Stream.of(
                changedFact(base, key, true), changedFact(base, key, 1), changedFact(base, key, null),
                changedFact(base, key, Map.of()), changedFact(base, key, List.of()), changedFact(base, key, "MAYBE"),
                changedFact(base, key, "unknown"), changedFact(base, key, " YES "), missingFact(base, key)));
        Stream<String> uncertain = Stream.of(changed(base, "facts", Map.of()),
                changedFact(base, "private", "UNKNOWN"), changed(base, "uncertainFields", List.of(1)),
                changed(base, "uncertainFields", List.of(Map.of())), changed(base, "uncertainFields", java.util.Arrays.asList((Object) null)),
                changed(base, "uncertainFields", List.of()), changed(base, "uncertainFields", List.of("sharedInformation")),
                changed(base, "uncertainFields", List.of("sharedInformation", "sharedInformation")),
                changed(base, "uncertainFields", List.of("sharedInformation", "installedApp", "transferredMoney")),
                changed(base, "uncertainFields", List.of("sharedInformation", "private")));
        return Stream.of(envelope, types, factValues, uncertain).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsMalformedFactsAndInconsistentUncertainty(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure(); server.verify();
    }

    @Test
    void fullyKnownFactsRequireExplicitEmptyUncertainty() {
        server.expect(anything()).andRespond(withSuccess("""
                {"success":true,"data":{"facts":{"transferredMoney":"NO","contactContinues":"YES",
                "sharedInformation":"NO","installedApp":"NO"},"uncertainFields":[]}}""", MediaType.APPLICATION_JSON));
        assertTrue(client.extractSolutionFacts(request).uncertainFields().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 429, 500, 503})
    void rejectsHttpFailuresEvenWithSuccessBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(RESPONSE));
        assertPrivateFailure(); server.verify();
    }

    @Test
    void rejectsHtmlAndTransportFailureWithoutRetainingCause() {
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        assertPrivateFailure(); assertPrivateFailure(); server.verify();
    }

    @Test
    void noAutomaticRetryAndSameIdWhenCallerRetries() {
        expectRequest().andRespond(withServiceUnavailable());
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        assertNotNull(client.extractSolutionFacts(request));
        server.verify();
    }

    @Test
    void nullRequestIsInputErrorWithoutHttpCall() {
        assertThrows(IllegalArgumentException.class, () -> client.extractSolutionFacts(null));
        server.verify();
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://ai.test/internal/ai/solutions/extract"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", request.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"description\":\"송금 후 연락이 끊겼어요.\",\"knownFacts\":{}}", JsonCompareMode.STRICT));
    }

    private void assertPrivateFailure() {
        var error = assertThrows(AiUnavailableException.class, () -> client.extractSolutionFacts(request));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains(request.description()));
    }

    private static String changed(JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data")).set(field, new ObjectMapper().valueToTree(value));
        return root.toString();
    }

    private static String changedFact(JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data").path("facts")).set(field, new ObjectMapper().valueToTree(value));
        return root.toString();
    }

    private static String missing(JsonNode original, String field) {
        var root = original.deepCopy(); ((ObjectNode) root.path("data")).remove(field); return root.toString();
    }

    private static String missingFact(JsonNode original, String field) {
        var root = original.deepCopy(); ((ObjectNode) root.path("data").path("facts")).remove(field); return root.toString();
    }
}
