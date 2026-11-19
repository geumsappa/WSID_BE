package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistStatus;
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

/** 실제 JDK HTTP 전송으로 UTF-8 본문·헤더·타임아웃을 확인해요. */
class HttpAiClientSolutionAssistTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedId = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedProtocol = new AtomicReference<>();
    private final AtomicReference<String> receivedUpgrade = new AtomicReference<>();
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(HttpAiClient.SOLUTION_ASSIST_PATH, exchange -> {
            calls.incrementAndGet();
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedProtocol.set(exchange.getProtocol());
            receivedUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = """
                    {"success":true,"data":{"status":"NEED_USER_INPUT","questionIntent":"GENERAL",
                    "clarification":{"factKey":"installedApp","content":"앱을 설치했나요?","options":["YES","NO","UNKNOWN"]},
                    "candidateReferences":[],"reasonCode":null}}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
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
    void sendsUtf8BodyAndHeadersOverRealHttp() {
        var request = request();
        var result = client(Duration.ofSeconds(2)).planSolutionAssist(request);
        assertEquals(SolutionAssistStatus.NEED_USER_INPUT, result.status());
        assertEquals("앱을 설치했나요?", result.clarification().content());
        assertEquals("transport-key", receivedKey.get());
        assertEquals(request.requestId().toString(), receivedId.get());
        var body = new ObjectMapper().readTree(receivedBody.get());
        assertEquals("PLAN", body.path("phase").asString());
        assertEquals("앱은 어떻게 하나요?", body.path("question").asString());
        assertFalse(body.has("requestId"));
        // uvicorn은 h2c 업그레이드 요청의 본문을 읽지 못해 422를 돌려줘요. 운영 경로는 HTTP/1.1이어야 해요.
        assertEquals("HTTP/1.1", receivedProtocol.get());
        assertNull(receivedUpgrade.get());
        // 운영 경로(기본 RestClient 변환기)에서도 null 필드를 빼지 않고 계약의 일곱 키(fraudType 포함)를 그대로 보내야 해요.
        var keys = new java.util.TreeSet<String>();
        body.propertyNames().forEach(keys::add);
        assertEquals(new java.util.TreeSet<>(List.of("phase", "question", "questionIntent", "facts",
                "askedFactKeys", "remainingClarifications", "fraudType")), keys);
        assertTrue(body.path("fraudType").isNull());
        assertTrue(body.path("questionIntent").isNull());
        assertEquals(4, body.path("facts").size());
    }

    @Test
    void readTimeoutBecomesPrivateFailureWithoutRetry() {
        delayMillis = 1_000;
        var error = assertThrows(AiUnavailableException.class,
                () -> client(Duration.ofMillis(200)).planSolutionAssist(request()));
        assertNull(error.getCause());
        assertEquals(1, calls.get());
    }

    @Test
    void assistTimeoutCanBeConfiguredIndependently() {
        delayMillis = 1_000;
        var properties = new AiProperties("http", baseUrl, "transport-key", Duration.ofSeconds(1),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(200));
        assertThrows(AiUnavailableException.class, () -> new HttpAiClient(properties).planSolutionAssist(request()));
        assertEquals(1, calls.get());
    }

    @Test
    void assistTimeoutDefaultsToCommonAndRejectsNonPositiveValues() {
        var common = new AiProperties("http", baseUrl, "transport-key", Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertEquals(Duration.ofSeconds(3), common.assistReadTimeout());
        for (var timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThrows(IllegalArgumentException.class, () -> new AiProperties("http", baseUrl, "transport-key",
                    Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3), timeout));
        }
    }

    private SolutionAssistPlanRequest request() {
        return new SolutionAssistPlanRequest("앱은 어떻게 하나요?", null, Map.of("transferredMoney", "YES",
                "sharedInformation", "NO", "installedApp", "UNKNOWN", "contactContinues", "UNKNOWN"),
                List.of(), 3, UUID.randomUUID());
    }

    private HttpAiClient client(Duration readTimeout) {
        var properties = new AiProperties("http", baseUrl, "transport-key", Duration.ofSeconds(1), readTimeout);
        return new HttpAiClient(properties, HttpAiClient.configuredBuilder(properties));
    }
}
