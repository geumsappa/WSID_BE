package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.VerifiedReference;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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

class HttpAiClientSolutionExplainTest {
    private static final String RESPONSE = """
            {"success":true,"data":{"explanation":"검토된 대응 카드의 순서와 준비 항목을 확인하세요.",
            "referenceCodes":["REF_1"]}}""";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(new AiProperties("http", "http://ai.test", "test-key",
            Duration.ofSeconds(2), Duration.ofSeconds(20)), builder);
    private final SolutionExplainRequest request = new SolutionExplainRequest(Map.of("transferredMoney", "YES"),
            List.of(new VerifiedReference("REF_1", "설명 예시", "검토된 준비 항목을 확인합니다.")), UUID.randomUUID());

    @Test
    void sendsExactContractAndHeadersWithoutBodyRequestId() {
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = client.explainSolution(request);
        assertEquals(List.of("REF_1"), result.referenceCodes());
        assertEquals("검토된 대응 카드의 순서와 준비 항목을 확인하세요.", result.explanation());
        server.verify();
    }

    @Test
    void acceptsSubsetOfApprovedRequestCodesAndNeverMutatesRequest() {
        var requestWithTwo = new SolutionExplainRequest(Map.of("installedApp", "UNKNOWN"),
                List.of(request.verifiedReferences().getFirst(), new VerifiedReference("REF_2", "제목", "문단")), UUID.randomUUID());
        server.expect(anything()).andRespond(withSuccess(RESPONSE.replace("REF_1", "REF_2"), MediaType.APPLICATION_JSON));
        assertEquals(List.of("REF_2"), client.explainSolution(requestWithTwo).referenceCodes());
        assertEquals(2, requestWithTwo.verifiedReferences().size());
        assertEquals(Map.of("installedApp", "UNKNOWN"), requestWithTwo.facts());
    }

    @Test
    void ignoresExtraResponseFieldsRatherThanRetainingPrivateEvidence() {
        server.expect(anything()).andRespond(withSuccess(RESPONSE.replace("\"data\":{",
                "\"rawDescription\":\"private\",\"data\":{\"evidence\":\"private\","), MediaType.APPLICATION_JSON));
        String serialized = new ObjectMapper().writeValueAsString(client.explainSolution(request));
        assertFalse(serialized.contains("private"));
        assertFalse(serialized.contains("evidence"));
    }

    static Stream<String> invalidResponses() {
        var base = new ObjectMapper().readTree(RESPONSE);
        Stream<String> envelope = Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                "{\"success\":true,\"data\":[]}", "{\"success\":true,\"data\":null}",
                RESPONSE.replace("\"success\":true", "\"success\":\"true\""),
                RESPONSE.replace("\"success\":true", "\"success\":1"),
                RESPONSE.replace("\"success\":true", "\"success\":false"),
                "{\"success\":false,\"error\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"private\"}}");
        Stream<String> explanation = Stream.of(changed(base, "explanation", null), missing(base, "explanation"),
                changed(base, "explanation", true), changed(base, "explanation", 1), changed(base, "explanation", Map.of()),
                changed(base, "explanation", List.of()), changed(base, "explanation", ""),
                changed(base, "explanation", " \n\t "), changed(base, "explanation", "x".repeat(2001)));
        Stream<String> codes = Stream.of(changed(base, "referenceCodes", null), missing(base, "referenceCodes"),
                changed(base, "referenceCodes", "REF_1"), changed(base, "referenceCodes", Map.of()),
                changed(base, "referenceCodes", true), changed(base, "referenceCodes", List.of(1)),
                changed(base, "referenceCodes", List.of(true)), changed(base, "referenceCodes", List.of(Map.of())),
                changed(base, "referenceCodes", Arrays.asList((Object) null)), changed(base, "referenceCodes", List.of()),
                changed(base, "referenceCodes", List.of("")), changed(base, "referenceCodes", List.of("ref_1")),
                changed(base, "referenceCodes", List.of(" REF_1 ")), changed(base, "referenceCodes", List.of("REF-1")),
                changed(base, "referenceCodes", List.of("A".repeat(101))), changed(base, "referenceCodes", List.of("REF_1", "REF_1")),
                changed(base, "referenceCodes", List.of("REF_NEW")), changed(base, "referenceCodes", List.of("REF_1", "REF_NEW")),
                changed(base, "referenceCodes", IntStream.range(0, 11).mapToObj(i -> "REF_" + i).toList()));
        return Stream.of(envelope, explanation, codes).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidTypesValuesOrRequestReferenceMismatch(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertPrivateFailure(); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.invalid", "010-0000-0000", "[내용](/path)", "<a href='/path'>내용</a>", "ｗｗｗ．ｅｘａｍｐｌｅ．ｉｎｖａｌｉｄ"})
    void validJsonWithForbiddenOutputStillFails(String explanation) {
        server.expect(anything()).andRespond(withSuccess(changed(new ObjectMapper().readTree(RESPONSE), "explanation", explanation),
                MediaType.APPLICATION_JSON));
        assertPrivateFailure(); server.verify();
    }

    @Test
    void emptyReferencesRequireExplicitEmptyCodesButDoNotProveGrounding() {
        var emptyRequest = new SolutionExplainRequest(request.facts(), List.of(), UUID.randomUUID());
        server.expect(anything()).andRespond(withSuccess(RESPONSE.replace("[\"REF_1\"]", "[]"), MediaType.APPLICATION_JSON));
        assertEquals(List.of(), client.explainSolution(emptyRequest).referenceCodes());
        server.verify();
    }

    @Test
    void emptyReferencesCannotReturnUnrequestedCode() {
        server.expect(anything()).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertThrows(AiUnavailableException.class,
                () -> client.explainSolution(new SolutionExplainRequest(request.facts(), List.of(), UUID.randomUUID())));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 429, 500, 503})
    void rejectsHttpErrorsEvenWithSuccessfulBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(RESPONSE));
        assertPrivateFailure(); server.verify();
    }

    @Test
    void htmlAndTransportExceptionsDoNotKeepPrivateCause() {
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        assertPrivateFailure(); assertPrivateFailure(); server.verify();
    }

    @Test
    void noAutomaticRetryAndSameIdWhenCallerRetries() {
        expectRequest().andRespond(withServiceUnavailable());
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertPrivateFailure();
        assertNotNull(client.explainSolution(request));
        server.verify();
    }

    @Test
    void nullRequestDoesNotMakeHttpCall() {
        assertThrows(IllegalArgumentException.class, () -> client.explainSolution(null));
        server.verify();
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://ai.test/internal/ai/solutions/explain"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", request.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"facts":{"transferredMoney":"YES"},"verifiedReferences":[{"code":"REF_1",
                        "title":"설명 예시","excerpt":"검토된 준비 항목을 확인합니다."}]}""", JsonCompareMode.STRICT));
    }

    private void assertPrivateFailure() {
        var error = assertThrows(AiUnavailableException.class, () -> client.explainSolution(request));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains(request.verifiedReferences().getFirst().excerpt()));
    }

    private static String changed(JsonNode original, String field, Object value) {
        var root = original.deepCopy();
        ((ObjectNode) root.path("data")).set(field, new ObjectMapper().valueToTree(value));
        return root.toString();
    }

    private static String missing(JsonNode original, String field) {
        var root = original.deepCopy(); ((ObjectNode) root.path("data")).remove(field); return root.toString();
    }
}
