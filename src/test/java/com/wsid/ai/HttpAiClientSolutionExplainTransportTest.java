package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SolutionExplainRequest;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import com.wsid.ai.dto.VerifiedReference;
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

class HttpAiClientSolutionExplainTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedId = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile long delayMillis;
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(HttpAiClient.SOLUTION_EXPLAIN_PATH, exchange -> {
            calls.incrementAndGet();
            receivedMethod.set(exchange.getRequestMethod());
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"success":true,"data":{"explanation":"검토된 대응 카드의 준비 항목을 확인하세요.","referenceCodes":["REF_1"]}}""");
        });
        server.createContext(HttpAiClient.EVALUATION_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, """
                    {"success":true,"data":{"overallFeedback":"평가","strengths":[],"improvements":[],"turnFeedback":[],
                    "recommendations":[],"mockPageEvaluation":{"openedPage":false,"attemptedMockLogin":false,"checkedOfficialChannel":false}}}""");
        });
        server.createContext(HttpAiClient.SCENARIO_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, """
                    {"success":true,"data":{"scenario":{"title":"훈련","description":"가상 상황"},"mockPageTemplateCode":"BANK_NOTICE"}}""");
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
    void sendsActualUtf8RequestAndHeadersWithoutBodyId() {
        var request = request();
        var result = client(Duration.ofSeconds(2), Duration.ofSeconds(2)).explainSolution(request);
        assertEquals(List.of("REF_1"), result.referenceCodes());
        assertEquals("POST", receivedMethod.get());
        assertEquals("test-key", receivedKey.get());
        assertEquals(request.requestId().toString(), receivedId.get());
        var body = new ObjectMapper().readTree(receivedBody.get());
        assertEquals(2, body.size());
        assertEquals("YES", body.path("facts").path("transferredMoney").asString());
        assertEquals("검토된 설명용 문단", body.path("verifiedReferences").get(0).path("excerpt").asString());
        assertFalse(receivedBody.get().contains("requestId"));
        assertFalse(receivedBody.get().contains(request.requestId().toString()));
        assertEquals(1, calls.get());
    }

    @Test
    void commonTimeoutIsUsedInsteadOfLongerScenarioAndEvaluationTimeouts() {
        delayMillis = 500;
        var client = client(Duration.ofMillis(150), Duration.ofSeconds(2));
        var error = assertThrows(AiUnavailableException.class, () -> client.explainSolution(request()));
        assertNull(error.getCause());
        assertNotNull(client.generateScenario(scenarioRequest()));
        assertNotNull(client.evaluateTraining(evaluationRequest()));
        assertEquals(1, calls.get());
    }

    @Test
    void shortSpecializedLimitsDoNotShortenExplanationTimeout() {
        delayMillis = 500;
        var client = client(Duration.ofSeconds(2), Duration.ofMillis(150));
        assertNotNull(client.explainSolution(request()));
        assertThrows(AiUnavailableException.class, () -> client.generateScenario(scenarioRequest()));
        assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(evaluationRequest()));
    }

    @Test
    void actual503FailsWithoutRetryOrRetainingResponseCause() {
        status = 503;
        var error = assertThrows(AiUnavailableException.class,
                () -> client(Duration.ofSeconds(2), Duration.ofSeconds(2)).explainSolution(request()));
        assertNull(error.getCause());
        assertEquals(1, calls.get());
    }

    @Test
    void fakeModeMakesNoHttpCalls() {
        var client = new AiClientConfig().aiClient(new AiProperties("fake", baseUrl, "test-key", Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertEquals(List.of("REF_1"), client.explainSolution(request()).referenceCodes());
        assertEquals(0, calls.get());
    }

    private AiClient client(Duration common, Duration specialized) {
        return new AiClientConfig().aiClient(new AiProperties("http", baseUrl, "test-key", Duration.ofSeconds(1), common, specialized, specialized));
    }

    private SolutionExplainRequest request() {
        return new SolutionExplainRequest(Map.of("transferredMoney", "YES"),
                List.of(new VerifiedReference("REF_1", "제목", "검토된 설명용 문단")), UUID.randomUUID());
    }

    private ScenarioGenerateRequest scenarioRequest() {
        return new ScenarioGenerateRequest(Map.of("activity", "중고거래"), "SECONDHAND_TRADE", UUID.randomUUID());
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
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
