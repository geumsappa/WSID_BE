package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.wsid.ai.dto.ScenarioGenerateRequest;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class HttpAiClientScenarioTest {
    private static final String RESPONSE = """
            {"success":true,"data":{"scenario":{"title":"중고거래 안전 확인 훈련","description":"가상 거래 상황"},
            "mockPageTemplateCode":"BANK_NOTICE"}}""";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAiClient client = new HttpAiClient(new AiProperties("http", "http://ai.test", "test-key",
            Duration.ofSeconds(2), Duration.ofSeconds(20)), builder);
    private final ScenarioGenerateRequest request = new ScenarioGenerateRequest(
            Map.of("activity", "중고거래"), "SECONDHAND_TRADE", UUID.randomUUID());

    @Test
    void sendsContractHeadersAndJsonWithoutRequestIdAndUnwrapsData() {
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = client.generateScenario(request);
        assertEquals("중고거래 안전 확인 훈련", result.scenario().title());
        assertEquals("가상 거래 상황", result.scenario().description());
        assertEquals("BANK_NOTICE", result.mockPageTemplateCode());
        server.verify();
    }

    @Test
    void callerRetryKeepsSameIdWithoutAutomaticRetries() {
        expectRequest().andRespond(withServiceUnavailable());
        expectRequest().andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        assertThrows(AiUnavailableException.class, () -> client.generateScenario(request));
        assertNotNull(client.generateScenario(request));
        server.verify();
    }

    @Test
    void ignoresAdditionalFieldsAndChecksOnlyTemplateFormatInFirstPhase() {
        server.expect(anything()).andRespond(withSuccess("""
                {"success":true,"extra":1,"data":{"extra":{},"scenario":{"title":"제목","description":"설명","extra":1},
                "mockPageTemplateCode":"UNREGISTERED_2"}}""", MediaType.APPLICATION_JSON));
        assertEquals("UNREGISTERED_2", client.generateScenario(request).mockPageTemplateCode());
        server.verify();
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "{", "null", "[]", "{}", "{\"success\":true}",
                RESPONSE.replace("\"success\":true", "\"success\":\"true\""),
                RESPONSE.replace("\"success\":true", "\"success\":1"),
                RESPONSE.replace("\"success\":true", "\"success\":null"),
                RESPONSE.replace("\"success\":true", "\"success\":false"),
                "{\"success\":true,\"data\":[]}",
                "{\"success\":true,\"data\":{\"scenario\":\"wrong\",\"mockPageTemplateCode\":\"BANK_NOTICE\"}}",
                "{\"success\":true,\"data\":{\"scenario\":{},\"mockPageTemplateCode\":\"BANK_NOTICE\"}}",
                data(12, "설명", "BANK_NOTICE"), data("제목", false, "BANK_NOTICE"),
                data("제목", "설명", 123), data("", "설명", "BANK_NOTICE"),
                data("제목", " \n ", "BANK_NOTICE"), data("x".repeat(101), "설명", "BANK_NOTICE"),
                data("제목", "x".repeat(1001), "BANK_NOTICE"), data("제목", "설명", ""),
                data("제목", "설명", "bank-notice"), data("제목", "설명", "BANK NOTICE"),
                data("제목", "설명", "A".repeat(51)), data("제목", "설명", "은행"),
                "{\"success\":false,\"error\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"private\"}}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidResponseWithoutRetainingBody(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var error = assertThrows(AiUnavailableException.class, () -> client.generateScenario(request));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.getMessage().contains("private"));
        server.verify();
    }

    @Test
    void acceptsLengthBoundaries() {
        server.expect(anything()).andRespond(withSuccess(
                data("x".repeat(100), "y".repeat(1000), "A".repeat(50)), MediaType.APPLICATION_JSON));
        assertEquals(50, client.generateScenario(request).mockPageTemplateCode().length());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 422, 500, 503})
    void rejectsHttpFailuresEvenWithSuccessBody(int status) {
        server.expect(anything()).andRespond(withRawStatus(status).body(RESPONSE));
        var error = assertThrows(AiUnavailableException.class, () -> client.generateScenario(request));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("중고거래"));
        server.verify();
    }

    @Test
    void rejectsHtmlAndTransportTimeout() {
        server.expect(anything()).andRespond(withSuccess("<html>private</html>", MediaType.TEXT_HTML));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private")));
        for (int i = 0; i < 2; i++) {
            var error = assertThrows(AiUnavailableException.class, () -> client.generateScenario(request));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private"));
        }
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectRequest() {
        return server.expect(requestTo("http://ai.test/internal/ai/scenarios/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-key"))
                .andExpect(header("X-Request-Id", request.requestId().toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"facts\":{\"activity\":\"중고거래\"},\"fraudTypeCode\":\"SECONDHAND_TRADE\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    private static String data(Object title, Object description, Object template) {
        return new ObjectMapper().writeValueAsString(Map.of("success", true,
                "data", Map.of("scenario", Map.of("title", title, "description", description),
                        "mockPageTemplateCode", template)));
    }
}
