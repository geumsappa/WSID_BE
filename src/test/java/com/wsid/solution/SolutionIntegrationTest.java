package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.WsidApplication;
import com.wsid.auth.mail.VerificationMailSender;
import com.wsid.auth.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 서버·PostgreSQL·Redis 세션으로 비회원 GET csrf → POST 상담 시작과 소유권을 확인해요. */
@Tag("integration")
@ActiveProfiles("local")
@SpringBootTest(classes = WsidApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SolutionIntegrationTest {
    private static final String NAMESPACE = "wsid:test:solution:" + UUID.randomUUID();
    private static final String PASSWORD = "examplePassword!1";

    @Autowired Environment environment;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @MockitoBean VerificationMailSender mail;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> delivered = new ConcurrentHashMap<>();
    private final List<String> emails = new ArrayList<>();
    private final List<Long> created = new ArrayList<>();
    private HttpClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.session.data.redis.namespace", () -> NAMESPACE + ":session");
        registry.add("wsid.auth.redis-namespace", () -> NAMESPACE + ":verification");
        registry.add("wsid.solution.anonymous-owner-namespace", () -> NAMESPACE + ":owner");
        registry.add("wsid.ai.mode", () -> "fake");
    }

    @BeforeEach
    void prepare() {
        client = HttpClient.newHttpClient();
        doAnswer(i -> { delivered.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(mail).send(anyString(), anyString(), any(Duration.class));
    }

    @AfterEach
    void cleanup() {
        client.close();
        for (long id : created) {
            jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
            jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
        }
        for (String email : emails) users.findByEmail(email).ifPresent(users::delete);
        var keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys); // 테스트 전용 namespace만 삭제
    }

    @Test
    void authCsrfStaysMemberOnlyAndAnonymousRestoreFollowsSessionCookie() throws Exception {
        var guestAuthCsrf = send(request("/api/auth/csrf", null).GET().build());
        assertEquals(401, guestAuthCsrf.statusCode(), "LSH AUTH의 /api/auth/csrf는 그대로 회원 전용이에요.");

        var browser = csrf(null);
        var created = send(start(browser.cookie, browser, null));
        long id = track(json.readTree(created.body()).path("data").path("solutionId").asLong());
        var restored = get("/api/solutions/sessions/" + id, browser.cookie);
        assertEquals(200, restored.statusCode(), restored.body());
        JsonNode before = json.readTree(created.body()).path("data");
        JsonNode after = json.readTree(restored.body()).path("data");
        assertEquals(before.path("facts"), after.path("facts"));
        assertEquals(before.path("nextQuestion"), after.path("nextQuestion"));
        assertEquals(2, after.path("messages").size());
        assertTrue(restored.headers().firstValue("Cache-Control").orElse("").contains("no-store"));

        var stranger = csrf(null);
        assertEquals(404, get("/api/solutions/sessions/" + id, stranger.cookie).statusCode());
        var noCookie = get("/api/solutions/sessions/" + id, null);
        assertEquals(404, noCookie.statusCode());
        assertTrue(noCookie.headers().firstValue("Set-Cookie").isEmpty(), "쿠키 없는 조회는 새 세션을 만들지 않아요.");
        assertEquals(404, get("/api/solutions/sessions/abc", browser.cookie).statusCode());

        // 세션이 만료·삭제되면 같은 쿠키여도 복원할 수 없어요.
        var sessionKeys = redis.keys(NAMESPACE + ":session:*");
        if (sessionKeys != null && !sessionKeys.isEmpty()) redis.delete(sessionKeys);
        assertEquals(404, get("/api/solutions/sessions/" + id, browser.cookie).statusCode());
    }

    @Test
    void anonymousCsrfThenStartKeepsOwnerPerSessionAndRejectsMissingToken() throws Exception {
        var first = csrf(null);
        assertNotNull(first.cookie);
        assertEquals("X-CSRF-TOKEN", first.header);
        assertFalse(redis.keys(NAMESPACE + ":session:*").isEmpty(), "비회원 CSRF 발급 시 Redis 세션이 생겨요.");

        var denied = send(start(first.cookie, null, null));
        assertEquals(403, denied.statusCode());
        assertEquals("CSRF_TOKEN_INVALID", json.readTree(denied.body()).path("code").asString());

        var created = send(start(first.cookie, first, null));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode data = json.readTree(created.body()).path("data");
        assertEquals(0, data.path("stateVersion").asInt());
        assertEquals("transferredMoney", data.path("nextQuestion").path("factKey").asString());
        assertFalse(created.body().contains("anonymous"));
        long a1 = track(data.path("solutionId").asLong());

        var describedRequest = start(first.cookie, first, "{\"description\":\"송금 후 연락이 끊겼어요.\"}");
        var described = send(describedRequest);
        // The production YAML now supplies the reviewed transfer response card.
        assertEquals(201, described.statusCode(), described.body());
        var describedData = json.readTree(described.body()).path("data");
        long describedId = track(describedData.path("solutionId").asLong());
        assertEquals("TRANSFER_RESPONSE", describedData.path("responseCards").get(0).path("code").asString());
        assertEquals("YES", describedData.path("facts").path("transferredMoney").asString());
        assertEquals(owner(a1), owner(describedId));

        long a2 = track(json.readTree(send(start(first.cookie, first, "{}")).body()).path("data").path("solutionId").asLong());
        UUID owner1 = owner(a1);
        assertNotNull(owner1);
        assertEquals(owner1, owner(a2));
        assertEquals(1, redis.keys(NAMESPACE + ":owner:*").size());

        var second = csrf(null);
        assertNotEquals(first.cookie, second.cookie);
        assertEquals(403, send(start(second.cookie, first, null)).statusCode());
        long b = track(json.readTree(send(start(second.cookie, second, null)).body()).path("data").path("solutionId").asLong());
        assertNotEquals(owner1, owner(b));

        var sensitive = send(start(second.cookie, second, "{\"description\":\"010-1234-5678 로 연락\"}"));
        assertEquals(400, sensitive.statusCode());
        assertEquals("SENSITIVE_DATA_DETECTED", json.readTree(sensitive.body()).path("code").asString());
        assertFalse(sensitive.body().contains("1234"));
    }

    @Test
    void afterLoginNewStartIsMemberOwnedAndAnonymousStartIsNotMigrated() throws Exception {
        var anonymous = csrf(null);
        long anonymousSolution = track(json.readTree(send(start(anonymous.cookie, anonymous, null)).body())
                .path("data").path("solutionId").asLong());
        UUID anonymousOwner = owner(anonymousSolution);

        String email = "solution-it-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        assertEquals(201, postJson("/api/auth/signup", Map.of("email", email, "password", PASSWORD, "gender", "MALE"), null).statusCode());
        assertEquals(200, postJson("/api/auth/email/verify", Map.of("email", email, "code", delivered.get(email)), null).statusCode());
        var login = postJson("/api/auth/login", Map.of("email", email, "password", PASSWORD), anonymous.cookie);
        assertEquals(200, login.statusCode(), login.body());
        String memberCookie = login.headers().firstValue("Set-Cookie").map(v -> v.split(";", 2)[0]).orElse(anonymous.cookie);

        // 회원은 기존 AUTH 발급 API(/api/auth/csrf) 토큰으로 상담을 시작해요(같은 세션 토큰 저장소).
        var member = csrf("/api/auth/csrf", memberCookie);
        assertEquals(403, send(start(memberCookie, null, null)).statusCode());
        var started = send(start(memberCookie, member, null));
        assertEquals(201, started.statusCode(), started.body());
        long memberSolution = track(json.readTree(started.body()).path("data").path("solutionId").asLong());
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertEquals(userId, jdbc.queryForObject("SELECT user_id FROM solution_sessions WHERE id = ?", Long.class, memberSolution));
        assertNull(owner(memberSolution));
        assertEquals(anonymousOwner, owner(anonymousSolution));
        assertNull(jdbc.queryForObject("SELECT user_id FROM solution_sessions WHERE id = ?", Long.class, anonymousSolution));

        // SOL-02 결정 3(방안 A): 로그인 뒤에는 회원 상담만 열리고 로그인 전 익명 상담은 404예요.
        assertEquals(200, get("/api/solutions/sessions/" + memberSolution, memberCookie).statusCode());
        assertEquals(404, get("/api/solutions/sessions/" + anonymousSolution, memberCookie).statusCode());
        var memberChoice = SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 0, "transferredMoney", "NO");
        assertEquals(200, send(message(memberSolution, member, memberChoice.toString())).statusCode());
        assertEquals(404, send(message(anonymousSolution, member, memberChoice.toString())).statusCode());
        assertEquals(200, send(completion(memberSolution, member, null)).statusCode());
        assertEquals(200, send(completion(memberSolution, member, "{}")).statusCode());
        assertEquals(404, send(completion(anonymousSolution, member, null)).statusCode());
        assertEquals(200, get("/api/auth/session", memberCookie).statusCode(), "상담 종료로 로그인 세션을 끝내지 않아요.");
    }

    @Test
    void anonymousMessagesPersistChoicesAndRejectDuplicateStaleForeignOrExpiredRequests() throws Exception {
        var browser = csrf(null);
        long id = track(json.readTree(send(start(browser.cookie, browser, null)).body()).path("data").path("solutionId").asLong());
        String first = SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 0, "transferredMoney", "NO").toString();
        var response = send(message(id, browser, first));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, json.readTree(response.body()).path("data").path("stateVersion").asInt());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var restored = json.readTree(get("/api/solutions/sessions/" + id, browser.cookie).body()).path("data");
        assertEquals("CHOICE", restored.path("messages").get(2).path("inputKind").asString());
        assertEquals("NO", restored.path("messages").get(2).path("selectedCode").asString());
        var duplicate = send(message(id, browser, first));
        assertEquals(409, duplicate.statusCode()); assertEquals("DUPLICATE_REQUEST", json.readTree(duplicate.body()).path("code").asString());
        var stale = send(message(id, browser, SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 0, "sharedInformation", "NO").toString()));
        assertEquals(409, stale.statusCode()); assertEquals("STATE_VERSION_CONFLICT", json.readTree(stale.body()).path("code").asString());
        var stranger = csrf(null);
        assertEquals(404, send(message(id, stranger, first)).statusCode());
        var noToken = request("/api/solutions/sessions/" + id + "/messages", browser.cookie)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(first)).build();
        assertEquals(403, send(noToken).statusCode());
        var failed = send(message(id, browser, SendSolutionMessageRequestTest.text(UUID.randomUUID(), 1, "[AI_FAIL]").toString()));
        assertEquals(503, failed.statusCode()); assertEquals("AI_SERVICE_UNAVAILABLE", json.readTree(failed.body()).path("code").asString());
        assertEquals(1, jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id = ?", Integer.class, id));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id));
        // 세션 만료 뒤 새 CSRF 세션만 받아도 상담 소유권이 새로 생기지 않아요.
        var sessionKeys = redis.keys(NAMESPACE + ":session:*");
        if (sessionKeys != null && !sessionKeys.isEmpty()) redis.delete(sessionKeys);
        var fresh = csrf(browser.cookie);
        assertEquals(404, send(message(id, fresh, first)).statusCode());
    }

    @Test
    void anonymousFactCorrectionPersistsReviewedCardsAndProtectsVersionAndOwnership() throws Exception {
        var browser = csrf(null);
        long id = track(json.readTree(send(start(browser.cookie, browser, null)).body()).path("data").path("solutionId").asLong());
        String body = UpdateSolutionFactsRequestTest.body(0, "transferredMoney", "NO", "sharedInformation", "UNKNOWN").toString();
        var response = send(facts(id, browser, body));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode data = json.readTree(response.body()).path("data");
        assertEquals(1, data.path("stateVersion").asInt());
        assertEquals("installedApp", data.path("nextQuestion").path("factKey").asString(), "UNKNOWN으로 고친 항목은 다시 묻지 않아요.");
        assertEquals(0, data.path("responseCards").size());
        var restored = json.readTree(get("/api/solutions/sessions/" + id, browser.cookie).body()).path("data");
        assertEquals(data.path("facts"), restored.path("facts"));
        assertEquals(data.path("nextQuestion"), restored.path("nextQuestion"));
        assertTrue(restored.path("messages").get(2).path("content").asString().startsWith("사실 수정: "));
        // A confirmed installation now returns and persists the reviewed app response card.
        var needsCard = send(facts(id, browser, UpdateSolutionFactsRequestTest.body(1, "installedApp", "YES").toString()));
        assertEquals(200, needsCard.statusCode(), needsCard.body());
        var updated = json.readTree(needsCard.body()).path("data");
        assertEquals(2, updated.path("stateVersion").asInt());
        assertEquals("YES", updated.path("facts").path("installedApp").asString());
        assertEquals("MALICIOUS_APP_RESPONSE", updated.path("responseCards").get(0).path("code").asString());
        var withCard = json.readTree(get("/api/solutions/sessions/" + id, browser.cookie).body()).path("data");
        assertEquals(updated.path("responseCards"), withCard.path("responseCards"));
        assertEquals(updated.path("facts"), withCard.path("facts"));
        var stale = send(facts(id, browser, body));
        assertEquals(409, stale.statusCode()); assertEquals("STATE_VERSION_CONFLICT", json.readTree(stale.body()).path("code").asString());
        assertEquals(404, send(facts(id, csrf(null), UpdateSolutionFactsRequestTest.body(2, "installedApp", "NO").toString())).statusCode());
        var noToken = request("/api/solutions/sessions/" + id + "/facts", browser.cookie).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, send(noToken).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id = ?", Integer.class, id));
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id));
        // 고친 버전으로 SOL-03 선택이 이어져요.
        var next = send(message(id, browser, SendSolutionMessageRequestTest.choice(UUID.randomUUID(), 2, "contactContinues", "NO").toString()));
        assertEquals(200, next.statusCode(), next.body());
        assertTrue(json.readTree(next.body()).path("data").path("nextQuestion").isNull());
    }

    private HttpRequest facts(long id, Csrf csrf, String body) {
        return request("/api/solutions/sessions/" + id + "/facts", csrf.cookie).header(csrf.header, csrf.token)
                .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest message(long id, Csrf csrf, String body) {
        return request("/api/solutions/sessions/" + id + "/messages", csrf.cookie).header(csrf.header, csrf.token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test
    void anonymousCompletionIsRepeatableAndKeepsOtherSessionsUsable() throws Exception {
        var browser = csrf(null);
        long id = track(json.readTree(send(start(browser.cookie, browser, null)).body()).path("data").path("solutionId").asLong());
        var before = jdbc.queryForMap("SELECT facts_json, card_codes_json, created_at FROM solution_sessions WHERE id = ?", id);
        var response = send(completion(id, browser, null));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body()).path("data");
        assertEquals(2, data.size()); assertEquals(id, data.path("solutionId").asLong());
        assertEquals("COMPLETED", data.path("status").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var after = jdbc.queryForMap("SELECT completed_at, updated_at, state_version FROM solution_sessions WHERE id = ?", id);
        assertEquals(200, send(completion(id, browser, "{}")).statusCode());
        assertEquals(after, jdbc.queryForMap("SELECT completed_at, updated_at, state_version FROM solution_sessions WHERE id = ?", id));
        assertEquals(before, jdbc.queryForMap("SELECT facts_json, card_codes_json, created_at FROM solution_sessions WHERE id = ?", id));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id));
        var restored = get("/api/solutions/sessions/" + id, browser.cookie);
        assertEquals(200, restored.statusCode()); assertTrue(json.readTree(restored.body()).path("data").path("nextQuestion").isNull());
        var lateMessage = send(message(id, browser, SendSolutionMessageRequestTest.text(UUID.randomUUID(), 1, "설명").toString()));
        assertEquals(409, lateMessage.statusCode()); assertEquals("SOLUTION_FINISHED", json.readTree(lateMessage.body()).path("code").asString());
        var correction = request("/api/solutions/sessions/" + id + "/facts", browser.cookie).header(browser.header, browser.token)
                .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString("{\"stateVersion\":1,\"changes\":{\"installedApp\":\"NO\"}}")).build();
        assertEquals(409, send(correction).statusCode());
        var next = send(start(browser.cookie, browser, null)); assertEquals(201, next.statusCode());
        long nextId = track(json.readTree(next.body()).path("data").path("solutionId").asLong());
        assertEquals(owner(id), owner(nextId));
        var stranger = csrf(null); assertEquals(404, send(completion(id, stranger, null)).statusCode());
        var noToken = request("/api/solutions/sessions/" + id + "/complete", browser.cookie).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, send(noToken).statusCode());
        var keys = redis.keys(NAMESPACE + ":session:*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        var fresh = csrf(browser.cookie); assertEquals(404, send(completion(id, fresh, null)).statusCode());
    }

    @Test
    void completionHandlesUnknownLengthBodyAndRejectsUnsupportedPayloads() throws Exception {
        var browser = csrf(null);
        long id = track(json.readTree(send(start(browser.cookie, browser, null)).body()).path("data").path("solutionId").asLong());
        for (String body : List.of("null", "[]", "{\"stateVersion\":0}", "{}{}", " ", "{")) {
            assertEquals(400, send(completion(id, browser, body)).statusCode());
        }
        var unknownLength = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(" {} "));
        assertEquals(-1, unknownLength.contentLength());
        var request = request("/api/solutions/sessions/" + id + "/complete", browser.cookie).header(browser.header, browser.token)
                .POST(unknownLength).build();
        assertEquals(200, send(request).statusCode());
    }

    private HttpRequest completion(long id, Csrf csrf, String body) {
        var builder = request("/api/solutions/sessions/" + id + "/complete", csrf.cookie).header(csrf.header, csrf.token);
        return builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private record Csrf(String cookie, String header, String token) {}

    /** 비회원은 상담 전용 발급 API를 써요. */
    private Csrf csrf(String cookie) throws Exception {
        return csrf("/api/solutions/csrf", cookie);
    }

    private Csrf csrf(String path, String cookie) throws Exception {
        var response = send(request(path, cookie).GET().build());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body()).path("data");
        String next = response.headers().firstValue("Set-Cookie").map(v -> v.split(";", 2)[0]).orElse(cookie);
        return new Csrf(next, data.path("headerName").asString(), data.path("token").asString());
    }

    private HttpRequest start(String cookie, Csrf csrf, String body) {
        var builder = request("/api/solutions/sessions", cookie);
        if (csrf != null) builder.header(csrf.header, csrf.token);
        if (body == null) return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        return builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send(request(path, cookie).GET().build());
    }

    private HttpResponse<String> postJson(String path, Object body, String cookie) throws Exception {
        return send(request(path, cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }

    private long track(long solutionId) {
        created.add(solutionId);
        return solutionId;
    }

    private UUID owner(long solutionId) {
        return jdbc.queryForObject("SELECT anonymous_owner_id FROM solution_sessions WHERE id = ?", UUID.class, solutionId);
    }

    private HttpRequest.Builder request(String path, String cookie) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(20));
        if (cookie != null) builder.header("Cookie", cookie);
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
