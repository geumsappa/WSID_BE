package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.EvaluationAction;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationMessage;
import com.wsid.ai.dto.SimulationReplyRequest;
import com.wsid.ai.dto.TrainingEvaluateRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** localhost 목업 FastAPI로 AI-05의 실제 전송·설정·응답 제한을 확인해요. 모든 값은 합성 데이터예요. */
class HttpAiClientEvaluationTransportTest {

    private static final String OK_RESPONSE = """
            {"success":true,"data":{"overallFeedback":"훈련 기록을 바탕으로 확인 방법을 돌아보세요.",
            "strengths":["안내를 바로 따르지 않았어요."],"improvements":[],"turnFeedback":[],
            "mockPageEvaluation":{"openedPage":true,"attemptedMockLogin":false,"checkedOfficialChannel":false},
            "recommendations":["공식 채널에서 다시 확인해 보세요."],"debugPrompt":"내부 전용"}}""";
    private static final String SIMULATION_RESPONSE = """
            {"success":true,"data":{"reply":"가상 대사","recognizedActions":[],"shouldFinish":false,"mockPage":null}}""";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class PropertiesConfiguration {}

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> response = new AtomicReference<>(OK_RESPONSE);
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

    @BeforeEach
    void startMockFastApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(HttpAiClient.EVALUATION_PATH, exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status.get(), response.get(), delay.get());
        });
        server.createContext(HttpAiClient.SIMULATION_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, SIMULATION_RESPONSE, delay.get());
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopMockFastApi() {
        server.stop(0);
        executor.close();
    }

    @Test
    void roundTripSendsContractRequestAndReturnsTypedResult() {
        AiClient client = httpClient(Duration.ofSeconds(2), Duration.ofSeconds(2));
        TrainingEvaluateRequest request = request();

        var result = client.evaluateTraining(request);

        assertEquals("POST", method.get());
        assertTrue(contentType.get().startsWith("application/json"));
        assertEquals("test-key", apiKey.get());
        assertEquals(request.requestId().toString(), requestId.get());

        JsonNode sent = new ObjectMapper().readTree(body.get());
        assertEquals(3, sent.size(), "본문에는 scenario·conversation·actions만 있어야 해요.");
        assertEquals("중고거래 안전 확인 훈련", sent.path("scenario").path("title").asString());
        assertEquals("가상 거래 상황", sent.path("scenario").path("description").asString());
        assertEquals(2, sent.path("conversation").size());
        assertEquals("assistant", sent.path("conversation").get(0).path("role").asString());
        assertEquals("user", sent.path("conversation").get(1).path("role").asString());
        assertEquals("안내의 내용을 확인해 보겠습니다.", sent.path("conversation").get(1).path("content").asString());
        assertEquals("MOCK_PAGE_OPEN", sent.path("actions").get(0).path("actionCode").asString());
        assertFalse(body.get().contains("requestId"));
        assertFalse(body.get().contains(request.requestId().toString()));

        assertEquals("훈련 기록을 바탕으로 확인 방법을 돌아보세요.", result.overallFeedback());
        assertEquals(List.of("안내를 바로 따르지 않았어요."), result.strengths());
        assertEquals(List.of(), result.improvements());
        assertEquals(List.of(), result.turnFeedback());
        assertTrue(result.mockPageEvaluation().openedPage());
        assertFalse(result.mockPageEvaluation().attemptedMockLogin());
        assertFalse(result.mockPageEvaluation().checkedOfficialChannel());
        assertEquals(List.of("공식 채널에서 다시 확인해 보세요."), result.recommendations());
        assertFalse(result.toString().contains("내부 전용"), "추가 응답 필드는 결과에 남지 않아요.");
    }

    @Test
    void failureEnvelopesOverRealHttpBecomeUnavailableWithoutCause() {
        AiClient client = httpClient(Duration.ofSeconds(2), Duration.ofSeconds(2));
        String failure = """
                {"success":false,"error":{"code":"AI_SERVICE_UNAVAILABLE","message":"원문이 담긴 오류"}}""";
        for (int code : List.of(200, 503, 500)) {
            status.set(code);
            response.set(failure);
            var error = assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request()));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("원문"));
        }
        status.set(200);
        response.set(OK_RESPONSE.replace("\"success\":true", "\"success\":false"));
        assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request()),
                "data가 정상이어도 success=false면 채택하지 않아요.");
        response.set(OK_RESPONSE.replace("\"success\":true", "\"success\":\"true\""));
        assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request()));
        response.set("<html>proxy error</html>");
        assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request()));
    }

    @Test
    void shortEvaluationTimeoutFailsWithoutShorteningSimulationTimeout() {
        delay.set(Duration.ofMillis(500));
        AiClient client = httpClient(Duration.ofSeconds(2), Duration.ofMillis(150));

        var error = assertThrows(AiUnavailableException.class, () -> client.evaluateTraining(request()));
        assertNull(error.getCause());
        assertEquals("가상 대사", client.replySimulation(simulationRequest()).reply());
        assertEquals(1, calls.get(), "클라이언트는 자동 재시도하지 않아요.");
    }

    @Test
    void longerEvaluationTimeoutWaitsWithoutExtendingSimulationTimeout() {
        delay.set(Duration.ofMillis(500));
        AiClient client = httpClient(Duration.ofMillis(150), Duration.ofSeconds(2));

        assertTrue(client.evaluateTraining(request()).mockPageEvaluation().openedPage());
        assertThrows(AiUnavailableException.class, () -> client.replySimulation(simulationRequest()));
    }

    @Test
    void bindsEvaluationTimeoutAndFallsBackToReadTimeout() {
        context().withPropertyValues("wsid.ai.mode=http", "wsid.ai.evaluation-read-timeout=45s")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var properties = ctx.getBean(AiProperties.class);
                    assertEquals(Duration.ofSeconds(45), properties.evaluationReadTimeout());
                    assertEquals(Duration.ofSeconds(20), properties.readTimeout());
                    assertEquals(Duration.ofSeconds(20), properties.scenarioReadTimeout());
                    assertInstanceOf(HttpAiClient.class, ctx.getBean(AiClient.class));
                });
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(Duration.ofSeconds(20), ctx.getBean(AiProperties.class).evaluationReadTimeout());
            assertInstanceOf(FakeAiClient.class, ctx.getBean(AiClient.class));
        });
        context().withPropertyValues("wsid.ai.evaluation-read-timeout=0s")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void rejectsNonPositiveEvaluationTimeoutAndKeepsLegacyConstructors() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThrows(IllegalArgumentException.class, () -> new AiProperties("http", "http://ai.test", "key",
                    Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(20), timeout));
        }
        var five = new AiProperties("http", "http://ai.test", "key", Duration.ofSeconds(2), Duration.ofSeconds(7));
        assertEquals(Duration.ofSeconds(7), five.evaluationReadTimeout());
        var six = new AiProperties("http", "http://ai.test", "key",
                Duration.ofSeconds(2), Duration.ofSeconds(7), Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), six.scenarioReadTimeout());
        assertEquals(Duration.ofSeconds(7), six.evaluationReadTimeout());
    }

    @Test
    void fakeModeNeverCallsFastApi() {
        AiClient client = new AiClientConfig().aiClient(new AiProperties("fake", baseUrl, "test-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertTrue(client.evaluateTraining(request()).mockPageEvaluation().openedPage());
        assertEquals(0, calls.get());
    }

    private AiClient httpClient(Duration readTimeout, Duration evaluationTimeout) {
        return new AiClientConfig().aiClient(new AiProperties("http", baseUrl, "test-key",
                Duration.ofSeconds(1), readTimeout, readTimeout, evaluationTimeout));
    }

    private static TrainingEvaluateRequest request() {
        return new TrainingEvaluateRequest(new Scenario("중고거래 안전 확인 훈련", "가상 거래 상황"),
                List.of(new SimulationMessage("assistant", "안내를 어떻게 확인하시겠어요?"),
                        new SimulationMessage("user", "안내의 내용을 확인해 보겠습니다.")),
                List.of(new EvaluationAction("MOCK_PAGE_OPEN")), UUID.randomUUID());
    }

    private static SimulationReplyRequest simulationRequest() {
        return new SimulationReplyRequest(new Scenario("훈련", "가상 상황"),
                List.of(), "확인해 보겠습니다.", 2, UUID.randomUUID());
    }

    private static void respond(HttpExchange exchange, int code, String body, Duration delay) throws IOException {
        try {
            if (!delay.isZero()) Thread.sleep(delay.toMillis());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    body.startsWith("<") ? "text/html" : "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 클라이언트가 시간 초과로 먼저 연결을 끊은 경우예요.
        } finally {
            exchange.close();
        }
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class, AiClientConfig.class)
                .withPropertyValues("wsid.ai.mode=fake", "wsid.ai.base-url=http://ai.test", "wsid.ai.api-key=test-key",
                        "wsid.ai.connect-timeout=2s", "wsid.ai.read-timeout=20s");
    }
}
