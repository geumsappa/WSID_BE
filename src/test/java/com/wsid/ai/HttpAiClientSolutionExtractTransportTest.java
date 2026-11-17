package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SolutionExtractRequest;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpAiClientSolutionExtractTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> receivedId = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(HttpAiClient.SOLUTION_EXTRACT_PATH, exchange -> {
            calls.incrementAndGet();
            receivedMethod.set(exchange.getRequestMethod());
            receivedId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"success":true,"data":{"facts":{"transferredMoney":"YES","contactContinues":"NO",
                    "sharedInformation":"UNKNOWN","installedApp":"UNKNOWN"},
                    "uncertainFields":["sharedInformation","installedApp"]}}""");
        });
        server.createContext(HttpAiClient.EVALUATION_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, """
                    {"success":true,"data":{"overallFeedback":"평가","strengths":[],"improvements":[],
                    "turnFeedback":[],"recommendations":[],"mockPageEvaluation":{"openedPage":false,
                    "attemptedMockLogin":false,"checkedOfficialChannel":false}}}""");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.close();
    }

    @Test
    void roundTripUsesExactHeadersAndUtf8Body() {
        var request = request();
        var result = client(Duration.ofSeconds(2), Duration.ofSeconds(2)).extractSolutionFacts(request);
        assertEquals("YES", result.facts().get("transferredMoney"));
        assertEquals("POST", receivedMethod.get());
        assertEquals(request.requestId().toString(), receivedId.get());
        assertEquals("test-key", receivedKey.get());
        var body = new ObjectMapper().readTree(receivedBody.get());
        assertEquals(2, body.size());
        assertEquals(request.description(), body.path("description").asString());
        assertEquals("YES", body.path("knownFacts").path("transferredMoney").asString());
        assertFalse(receivedBody.get().contains("requestId"));
        assertFalse(receivedBody.get().contains(request.requestId().toString()));
        assertEquals(1, calls.get());
    }

    @Test
    void solutionTimeoutUsesCommonLimitAndDoesNotExtendToEvaluationLimit() {
        delayMillis = 500;
        var client = client(Duration.ofMillis(150), Duration.ofSeconds(2));
        var error = assertThrows(AiUnavailableException.class, () -> client.extractSolutionFacts(request()));
        assertNull(error.getCause());
        assertNotNull(client.evaluateTraining(evaluationRequest()));
        assertEquals(1, calls.get());
    }

    @Test
    void shortEvaluationLimitDoesNotShortenSolutionLimit() {
        delayMillis = 500;
        var client = client(Duration.ofSeconds(2), Duration.ofMillis(150));
        assertNotNull(client.extractSolutionFacts(request()));
        assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(evaluationRequest()));
    }

    @Test
    void fakeModeDoesNotCallRemoteServer() {
        var client = new AiClientConfig().aiClient(new AiProperties("fake", baseUrl, "test-key",
                Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertEquals("YES", client.extractSolutionFacts(request()).facts().get("transferredMoney"));
        assertEquals(0, calls.get());
    }

    private AiClient client(Duration readTimeout, Duration evaluationTimeout) {
        return new AiClientConfig().aiClient(new AiProperties("http", baseUrl, "test-key",
                Duration.ofSeconds(1), readTimeout, Duration.ofSeconds(2), evaluationTimeout));
    }

    private SolutionExtractRequest request() {
        return new SolutionExtractRequest("송금 후 연락이 끊겼어요.", Map.of("transferredMoney", "YES"), UUID.randomUUID());
    }

    private TrainingEvaluateRequest evaluationRequest() {
        return new TrainingEvaluateRequest(new Scenario("훈련", "가상 상황"), List.of(),
                List.of(new EvaluationAction("MOCK_PAGE_OPEN")), UUID.randomUUID());
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
