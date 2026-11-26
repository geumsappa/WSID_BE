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
class SolutionAssistHttpIntegrationTest {
    private static final String NAMESPACE = "wsid:test:assist-http:" + UUID.randomUUID();
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
        upstream.createContext("/internal/ai/solutions/assist", exchange -> {
            CALLS.incrementAndGet();
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODY.set(raw); KEY.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            var body = new ObjectMapper().readTree(raw);
            String payload;
            if ("ANSWER".equals(body.path("phase").asString())) {
                payload = "{\"success\":true,\"data\":{\"status\":\"ANSWERED\",\"answer\":\"검토된 자료에 따른 안내입니다.\",\"referenceCodes\":[\"TEST_GUIDE\"],\"reasonCode\":null}}";
            } else if ("UNKNOWN".equals(body.path("facts").path("installedApp").asString())) {
                payload = "{\"success\":true,\"data\":{\"status\":\"NEED_USER_INPUT\",\"questionIntent\":\"DEVICE_SAFETY\",\"clarification\":{\"factKey\":\"installedApp\",\"content\":\"모델 질문\",\"options\":[\"YES\",\"NO\",\"UNKNOWN\"]},\"candidateReferences\":[],\"reasonCode\":null}}";
            } else {
                payload = "{\"success\":true,\"data\":{\"status\":\"EVIDENCE_READY\",\"questionIntent\":\"DEVICE_SAFETY\",\"clarification\":null,\"candidateReferences\":[{\"code\":\"TEST_GUIDE\",\"version\":\"1\"}],\"reasonCode\":null}}";
            }
            if ("timeout".equals(MODE.get())) try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] response=payload.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders("503".equals(MODE.get()) ? 503 : 200,response.length);
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException ignored) { } finally { exchange.close(); }
        });
        upstream.start();
        r.add("wsid.ai.mode", () -> "fake");
        r.add("wsid.ai.assist-mode", () -> "http");
        r.add("wsid.ai.assist-base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        r.add("wsid.ai.assist-api-key", () -> "assist-test-key");
        r.add("wsid.ai.assist-read-timeout", () -> "300ms");
        r.add("wsid.content.approved-references", () -> "classpath:test-content/assist/approved.json");
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

    private String question(int version) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"stateVersion\":" + version
            + ",\"purpose\":\"QUESTION\",\"inputKind\":\"TEXT\",\"content\":\"앱 관련 비공개 질문\"}";
    }
    @Test void browserToSpringToHttpAiAndBackWithRestoreAndAnswer() throws Exception {
        long id=start();
        var noCsrf=send(HttpRequest.newBuilder(uri(path(id))).header("Cookie",cookie).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(question(0))).build());
        assertEquals(403,noCsrf.statusCode());assertEquals(0,CALLS.get());
        var first=post(path(id),question(0));assertEquals(200,first.statusCode(),first.body());
        var data=json.readTree(first.body()).path("data");
        assertEquals("NEED_USER_INPUT",data.path("assistant").path("status").asString());assertTrue(data.path("nextQuestion").isNull());
        assertEquals(SolutionFactQuestion(),data.path("assistant").path("question").path("content").asString());
        String qid=data.path("assistant").path("question").path("questionId").asString();
        var restored=send(HttpRequest.newBuilder(uri("/api/solutions/sessions/"+id)).header("Cookie",cookie).GET().build());
        assertEquals(qid,json.readTree(restored.body()).path("data").path("assistant").path("question").path("questionId").asString());
        var stranger=send(HttpRequest.newBuilder(uri("/api/solutions/sessions/"+id)).GET().build());assertEquals(404,stranger.statusCode());
        String choice="{\"requestId\":\""+UUID.randomUUID()+"\",\"stateVersion\":1,\"purpose\":\"CLARIFICATION_ANSWER\",\"inputKind\":\"CHOICE\",\"questionId\":\""+qid+"\",\"selectedCode\":\"NO\"}";
        var second=post(path(id),choice);assertEquals(200,second.statusCode(),second.body());
        var after=json.readTree(second.body()).path("data");assertEquals("ANSWERED",after.path("assistant").path("status").asString());
        assertEquals("NO",after.path("facts").path("installedApp").asString());assertEquals(2,after.path("stateVersion").asInt());
        assertEquals(3,CALLS.get());assertEquals("assist-test-key",KEY.get());UUID.fromString(REQUEST_ID.get());
        var answer=json.readTree(BODY.get());assertEquals("ANSWER",answer.path("phase").asString());assertTrue(answer.path("question").isNull());
        assertEquals("TEST_GUIDE",answer.path("verifiedReferences").get(0).path("code").asString());
        // 확인 질문 뒤 후속 ANSWER는 question=null이지만, 저장된 원래 질문이 history로 함께 가요.
        assertEquals("USER",answer.path("history").get(0).path("role").asString());
        assertEquals("앱 관련 비공개 질문",answer.path("history").get(0).path("content").asString());
        assertEquals(409,post(path(id),choice).statusCode());
        // 질문 원문 행 하나와 AI 답변 행 하나가 남아 새로고침 때 복원돼요.
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=? AND safe_content='앱 관련 비공개 질문'",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=? AND sender='AI' AND purpose='ANSWER'",Integer.class,id));
    }
    private String SolutionFactQuestion() {return com.wsid.solution.domain.SolutionFact.INSTALLED_APP.question();}
    @ParameterizedTest @ValueSource(strings={"503","timeout"})
    void upstreamFailureDoesNotChangeState(String mode) throws Exception {
        long id=start();MODE.set(mode);assertEquals(503,post(path(id),question(0)).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id=?",Integer.class,id));
    }
}
