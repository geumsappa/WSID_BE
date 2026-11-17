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
        // 운영 카드 콘텐츠가 아직 없어 송금 YES 카드가 필요한 조합은 503이고 행을 만들지 않아요.
        assertEquals(503, described.statusCode(), described.body());
        assertEquals("RESPONSE_CONTENT_UNAVAILABLE", json.readTree(described.body()).path("code").asString());

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

        var member = csrf(memberCookie);
        assertEquals(403, send(start(memberCookie, null, null)).statusCode());
        var started = send(start(memberCookie, member, null));
        assertEquals(201, started.statusCode(), started.body());
        long memberSolution = track(json.readTree(started.body()).path("data").path("solutionId").asLong());
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertEquals(userId, jdbc.queryForObject("SELECT user_id FROM solution_sessions WHERE id = ?", Long.class, memberSolution));
        assertNull(owner(memberSolution));
        assertEquals(anonymousOwner, owner(anonymousSolution));
        assertNull(jdbc.queryForObject("SELECT user_id FROM solution_sessions WHERE id = ?", Long.class, anonymousSolution));
    }

    private record Csrf(String cookie, String header, String token) {}

    private Csrf csrf(String cookie) throws Exception {
        var response = send(request("/api/auth/csrf", cookie).GET().build());
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
