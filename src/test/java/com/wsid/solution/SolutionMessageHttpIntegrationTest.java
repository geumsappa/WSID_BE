package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.wsid.WsidApplication;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 브라우저 → 실제 Spring 서버·DB·Redis → 실제 HttpAiClient → localhost FastAPI 대역. */
@Tag("integration")
@ActiveProfiles("local")
@SpringBootTest(classes = WsidApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SolutionMessageHttpIntegrationTest {
    private static final String NAMESPACE = "wsid:test:sol03-http:" + UUID.randomUUID();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEY = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final String SUCCESS = """
            {"success":true,"data":{"facts":{"transferredMoney":"UNKNOWN","sharedInformation":"UNKNOWN",
            "installedApp":"UNKNOWN","contactContinues":"NO"},
            "uncertainFields":["transferredMoney","sharedInformation","installedApp"]}}
            """;
    private static HttpServer upstream;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> created = new ArrayList<>();
    private HttpClient browser;
    private String cookie;
    private String header;
    private String token;

    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(EXECUTOR);
        upstream.createContext("/internal/ai/solutions/extract", exchange -> {
            CALLS.incrementAndGet();
            BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            KEY.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")); METHOD.set(exchange.getRequestMethod());
            String mode = MODE.get();
            if ("timeout".equals(mode)) {
                try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String payload = switch (mode) {
                case "malformed" -> "{broken";
                case "stringSuccess" -> SUCCESS.replace("\"success\":true", "\"success\":\"true\"");
                default -> SUCCESS;
            };
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders("503".equals(mode) ? 503 : 200, response.length);
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException ignored) {
                // 클라이언트 타임아웃으로 연결이 닫힌 경우예요. 원문은 출력하지 않아요.
            } finally { exchange.close(); }
        });
        upstream.start();
        r.add("wsid.ai.mode", () -> "http");
        r.add("wsid.ai.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        r.add("wsid.ai.api-key", () -> "sol03-test-key");
        r.add("wsid.ai.read-timeout", () -> "300ms");
        r.add("spring.session.data.redis.namespace", () -> NAMESPACE + ":session");
        r.add("wsid.solution.anonymous-owner-namespace", () -> NAMESPACE + ":owner");
    }

    @BeforeEach void startBrowser() throws Exception {
        MODE.set("success"); CALLS.set(0); BODY.set(null);
        browser = HttpClient.newHttpClient();
        var csrf = send(HttpRequest.newBuilder(uri("/api/solutions/csrf")).GET().build());
        assertEquals(200, csrf.statusCode());
        cookie = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        var data = json.readTree(csrf.body()).path("data");
        header = data.path("headerName").asString(); token = data.path("token").asString();
    }

    @AfterEach void cleanup() {
        browser.close();
        for (long id : created) {
            jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
            jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
        }
        var keys = redis.keys(NAMESPACE + ":*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @AfterAll static void stopUpstream() { if (upstream != null) upstream.stop(0); EXECUTOR.close(); }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path); }
    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).header("Cookie", cookie).header(header, token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception { return browser.send(request, HttpResponse.BodyHandlers.ofString()); }
    private long start() throws Exception {
        var response = post("/api/solutions/sessions", "{}"); assertEquals(201, response.statusCode(), response.body());
        long id = json.readTree(response.body()).path("data").path("solutionId").asLong(); created.add(id); return id;
    }
    private String path(long id) { return "/api/solutions/sessions/" + id + "/messages"; }

    @Test void choiceSkipsHttpAndTextUsesConfirmedFactsUuidAndInternalKey() throws Exception {
        long id = start();
        var choice = post(path(id), SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 0, "transferredMoney", "NO").toString());
        assertEquals(200, choice.statusCode()); assertEquals(0, CALLS.get());
        UUID requestId = UUID.randomUUID();
        String secret = "외부로 전송하지만 저장하지 않을 비공개 설명";
        var text = post(path(id), SendSolutionMessageRequestTest.text(requestId, 1, secret).toString());
        assertEquals(200, text.statusCode(), text.body());
        assertEquals(2, json.readTree(text.body()).path("data").path("stateVersion").asInt());
        assertEquals("NO", json.readTree(text.body()).path("data").path("facts").path("transferredMoney").asString());
        assertEquals(1, CALLS.get()); assertEquals("POST", METHOD.get());
        assertEquals(requestId.toString(), REQUEST_ID.get()); assertEquals("sol03-test-key", KEY.get());
        var body = json.readTree(BODY.get());
        assertEquals(2, body.size()); assertEquals(secret, body.path("description").asString());
        assertEquals(json.readTree("{\"transferredMoney\":\"NO\"}"), body.path("knownFacts"));
        assertFalse(body.has("requestId"));
        // 텍스트 입력은 원문 행 하나로 남아요(새로고침 복원·assist history용).
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ? AND safe_content = ?", Integer.class, id, secret));
    }

    @ParameterizedTest @ValueSource(strings = {"503", "malformed", "stringSuccess", "timeout"})
    void upstreamFailuresAre503WithoutPartialWritesAndSameUuidCanBeRetried(String mode) throws Exception {
        long id = start(); UUID requestId = UUID.randomUUID();
        String input = SendSolutionMessageRequestTest.text(requestId, 0, "비공개 상세 설명").toString();
        MODE.set(mode);
        var failure = post(path(id), input);
        assertEquals(503, failure.statusCode(), failure.body());
        assertEquals("AI_SERVICE_UNAVAILABLE", json.readTree(failure.body()).path("code").asString());
        assertFalse(failure.body().contains("비공개")); assertEquals(1, CALLS.get());
        assertEquals(0, jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id));
        MODE.set("success");
        var retry = post(path(id), input);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(1, json.readTree(retry.body()).path("data").path("stateVersion").asInt());
    }
}
