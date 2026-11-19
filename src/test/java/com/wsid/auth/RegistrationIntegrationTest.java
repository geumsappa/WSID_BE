package com.wsid.auth;

import com.wsid.WsidApplication;
import com.wsid.auth.domain.UserStatus;
import com.wsid.auth.mail.VerificationMailSender;
import com.wsid.auth.repository.UserRepository;
import com.wsid.auth.repository.VerificationCodeStore;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration")
@ActiveProfiles("local")
@SpringBootTest(classes = WsidApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationIntegrationTest {
    private static final String NAMESPACE = "wsid:test:registration:" + UUID.randomUUID();
    private static final String PASSWORD = "examplePassword!1";
    @Autowired Environment environment;
    @Autowired UserRepository users;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean VerificationCodeStore codes;
    @Autowired PasswordEncoder encoder;
    @MockitoBean VerificationMailSender mail;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> delivered = new ConcurrentHashMap<>();
    private final List<String> addresses = new ArrayList<>();
    private HttpClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wsid.auth.redis-namespace", () -> NAMESPACE);
        registry.add("spring.session.data.redis.namespace", () -> NAMESPACE + ":login");
        registry.add("wsid.guides.catalog", () -> "classpath:guide-fixture.yml");
        registry.add("wsid.ai.mode", () -> "fake");
        registry.add("wsid.auth.resend-interval", () -> "60s");
        registry.add("wsid.auth.max-sends", () -> 3);
        registry.add("wsid.auth.max-attempts-per-code", () -> 3);
        registry.add("wsid.auth.max-attempts-per-window", () -> 4);
    }

    @BeforeEach
    void prepare() {
        client = HttpClient.newHttpClient();
        doAnswer(invocation -> {
            delivered.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mail).send(anyString(), anyString(), any(Duration.class));
    }

    @AfterEach
    void cleanup() {
        client.close();
        for (String email : addresses) users.findByEmail(email).ifPresent(users::delete);
        var keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys); // 테스트 전용 namespace만 삭제
    }

    @Test
    void corsPreflightAllowsLocalFrontendCredentialsAndRejectsOtherOrigins() throws Exception {
        var allowed = loginPreflight("http://localhost:8443");
        assertEquals(200, allowed.statusCode());
        assertEquals("http://localhost:8443", allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", allowed.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());

        var denied = loginPreflight("https://other.example");
        assertEquals(403, denied.statusCode());
        assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    private HttpResponse<String> loginPreflight(String origin) throws Exception {
        var preflight = request("/api/auth/login", null).header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        return client.send(preflight.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signupStoresHashAndVerificationActivatesOnce() throws Exception {
        String email = address();
        var created = signup("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
        assertEquals(201, created.statusCode());
        JsonNode data = json.readTree(created.body()).path("data");
        assertTrue(json.readTree(created.body()).path("success").asBoolean());
        assertEquals(email, data.path("email").asText());
        assertEquals("PENDING", data.path("status").asText());
        assertTrue(data.path("verificationRequired").asBoolean());
        assertFalse(created.body().contains("password"));
        var user = users.findByEmail(email).orElseThrow();
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertEquals("MALE", user.getGender());
        String code = delivered.get(email);
        assertTrue(code.matches("[0-9]{6}"));
        String hash = redis.opsForValue().get(key(email, "code"));
        assertNotEquals(code, hash);
        assertTrue(encoder.matches(code, hash));
        assertTrue(redis.getExpire(key(email, "code")) > 0);

        var verified = verifyCode(email, code);
        assertEquals(200, verified.statusCode());
        assertEquals("ACTIVE", json.readTree(verified.body()).path("data").path("status").asText());
        user = users.findByEmail(email).orElseThrow();
        assertTrue(user.canLogin());
        assertNotNull(user.getEmailVerifiedAt());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(email, "code"))));
        assertError(verifyCode(email, code), 400, "VERIFICATION_CODE_EXPIRED");
        assertError(signup(email), 409, "EMAIL_ALREADY_EXISTS");
    }

    @Test
    void loginRequiresVerifiedEmailAndSessionCheckRestoresUserFromCookie() throws Exception {
        String email = address();
        var credentials = Map.of("email", email, "password", PASSWORD);
        var guest = session(null);
        assertError(guest, 401, "LOGIN_REQUIRED");
        assertTrue(guest.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(201, signup(email).statusCode());
        var pending = post("/login", credentials);
        assertError(pending, 403, "EMAIL_NOT_VERIFIED");
        assertTrue(pending.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
        assertError(post("/login", Map.of("email", email, "password", "incorrect")), 401, "INVALID_CREDENTIALS");

        var loggedIn = post("/login", credentials);
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        var loginData = json.readTree(loggedIn.body()).path("data");
        var userId = users.findByEmail(email).orElseThrow().getId().longValue();
        assertEquals(userId, loginData.path("user").path("id").asLong());
        assertEquals(email, loginData.path("user").path("email").asText());
        assertFalse(loginData.has("authenticated"));
        String cookie = loggedIn.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("SESSION="));
        assertTrue(cookie.contains("HttpOnly"));
        String sessionCookie = cookie.split(";", 2)[0];
        var restored = session(sessionCookie);
        assertEquals(200, restored.statusCode(), restored.body());
        var data = json.readTree(restored.body()).path("data");
        assertTrue(data.path("authenticated").asBoolean());
        assertEquals(loginData.path("user"), data.path("user"));
        assertError(session(null), 401, "LOGIN_REQUIRED");

        var profile = getWithCookie("/api/users/me", sessionCookie);
        assertEquals(200, profile.statusCode(), profile.body());
        var profileData = json.readTree(profile.body()).path("data");
        assertEquals(email, profileData.path("email").asText());
        assertEquals("ACTIVE", profileData.path("status").asText());
        assertEquals(userId, profileData.path("id").asLong());
        assertFalse(profileData.has("userId"));
        assertFalse(profileData.has("emailVerifiedAt"));
        assertFalse(profileData.has("passwordHash"));

        assertError(logout(null, Map.of()), 401, "LOGIN_REQUIRED");
        assertEquals(403, logout(sessionCookie, Map.of()).statusCode());
        assertEquals(200, session(sessionCookie).statusCode());
        var csrf = getWithCookie("/api/auth/csrf", sessionCookie);
        assertEquals(200, csrf.statusCode(), csrf.body());
        var csrfData = json.readTree(csrf.body()).path("data");
        var loggedOut = logout(sessionCookie, Map.of(csrfData.path("headerName").asText(),
                csrfData.path("token").asText()));
        assertEquals(204, loggedOut.statusCode(), loggedOut.body());
        assertTrue(loggedOut.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertError(session(sessionCookie), 401, "LOGIN_REQUIRED");
        assertError(getWithCookie("/api/users/me", sessionCookie), 401, "LOGIN_REQUIRED");
    }

    private HttpResponse<String> logout(String cookie, Map<String, String> headers) throws Exception {
        var request = request("/api/auth/logout", cookie).POST(HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void trainingUsesSessionOwnerEvenOnLocalProfile() throws Exception {
        String path = "/api/training/sessions";
        assertError(getWithCookie(path + "/active", null), 401, "LOGIN_REQUIRED");
        String owner = activeCookie(address());
        String other = activeCookie(address());
        var start = request(path, owner).POST(HttpRequest.BodyPublishers.noBody());
        assertEquals(403, client.send(start.build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        var csrf = getWithCookie("/api/auth/csrf", owner);
        assertEquals(200, csrf.statusCode(), csrf.body());
        var token = json.readTree(csrf.body()).path("data");
        var created = client.send(start.header(token.path("headerName").asText(), token.path("token").asText()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        long trainingId = json.readTree(created.body()).path("sessionId").asLong();
        try {
            assertEquals(200, getWithCookie(path + "/" + trainingId, owner).statusCode());
            assertError(getWithCookie(path + "/" + trainingId, other), 404, "SESSION_NOT_FOUND");
            assertEquals(204, getWithCookie(path + "/active", other).statusCode());
        } finally {
            jdbc.update("DELETE FROM training_messages WHERE training_id = ?", trainingId);
            jdbc.update("DELETE FROM training_sessions WHERE id = ?", trainingId);
        }
    }

    private String activeCookie(String email) throws Exception {
        assertEquals(201, signup(email).statusCode());
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
        var login = post("/login", Map.of("email", email, "password", PASSWORD));
        assertEquals(200, login.statusCode(), login.body());
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    @Test
    void trainingContractCreatesRestoresAndOnlyShowsActiveOwnedSessions() throws Exception {
        String path = "/api/trainings";
        String owner = activeCookie(address());
        String other = activeCookie(address());
        assertError(getWithCookie(path + "/active", null), 401, "LOGIN_REQUIRED");
        assertTrue(data(getWithCookie(path + "/active", owner), 200).isNull());
        var unprotected = request(path, owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertEquals(403, client.send(unprotected.build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        var token = data(getWithCookie("/api/auth/csrf", owner), 200);
        var created = data(reportPost(path, owner, token, Map.of()), 201);
        long id = created.path("trainingId").asLong();
        try {
            assertEquals("DISCOVERY", created.path("phase").asText());
            assertEquals("ACTIVE", created.path("status").asText());
            assertEquals(0, created.path("stateVersion").asInt());
            assertEquals("AI", created.path("firstMessage").path("sender").asText());
            String firstMessage = created.path("firstMessage").path("content").asText();
            assertFalse(firstMessage.isBlank());

            var active = data(getWithCookie(path + "/active", owner), 200);
            assertEquals(id, active.path("trainingId").asLong());
            assertTrue(active.path("lastActivityAt").isString());
            assertTrue(data(getWithCookie(path + "/active", other), 200).isNull());
            assertError(getWithCookie(path + "/" + id, other), 404, "TRAINING_NOT_FOUND");

            var detail = data(getWithCookie(path + "/" + id, owner), 200);
            var opening = detail.path("messages").get(0);
            assertTrue(opening.path("id").asLong() > 0);
            assertEquals("TEXT", opening.path("inputKind").asText());
            assertEquals(firstMessage, opening.path("content").asText());
            assertEquals(0, detail.path("turnCount").asInt());
            assertTrue(detail.path("mockPage").isNull());
            assertTrue(detail.path("availableOptions").isArray());
            assertTrue(detail.path("availableOptions").isEmpty());

            jdbc.update("update training_messages set options_json = cast(? as jsonb) where id = ?",
                    "[{\"code\":\"CHECK\",\"label\":\"직접 확인\"}]", opening.path("id").asLong());
            var restored = data(getWithCookie(path + "/" + id, owner), 200);
            assertEquals("CHECK", restored.path("availableOptions").get(0).path("code").asText());

            jdbc.update("update training_sessions set expires_at = CURRENT_TIMESTAMP where id = ?", id);
            assertTrue(data(getWithCookie(path + "/active", owner), 200).isNull());
        } finally {
            jdbc.update("delete from training_messages where training_id = ?", id);
            jdbc.update("delete from training_sessions where id = ?", id);
        }
    }

    @Test
    void trainingWorkflowMovesThroughDiscoverySimulationActionsAndReport() throws Exception {
        String owner = activeCookie(address());
        String other = activeCookie(address());
        var token = data(getWithCookie("/api/auth/csrf", owner), 200);
        var created = data(reportPost("/api/trainings", owner, token, Map.of()), 201);
        long id = created.path("trainingId").asLong();
        String path = "/api/trainings/" + id;
        try {
            assertError(reportPost(path + "/scenario", owner, token, trainingBody(0, Map.of())),
                    409, "INVALID_TRAINING_PHASE");
            String discovery = path + "/discovery/messages";
            assertError(reportPost(discovery, owner, token, trainingBody(0, Map.of("content", "test@example.invalid"))),
                    400, "SENSITIVE_DATA_DETECTED");
            var first = trainingBody(0, Map.of("content", "중고거래 중 의심스러운 연락을 받았어요."));
            var response = data(reportPost(discovery, owner, token, first), 200);
            assertFalse(response.path("canGenerateScenario").asBoolean());
            assertError(reportPost(discovery, owner, token, first), 409, "DUPLICATE_REQUEST");
            assertError(reportPost(discovery, owner, token, trainingBody(0, Map.of("content", "이전 화면 요청"))),
                    409, "STATE_VERSION_CONFLICT");
            int version = response.path("stateVersion").asInt();
            for (String answer : List.of("거래 내용을 확인해 달라고 했어요.", "직접 확인하고 답하겠다고 했어요.")) {
                response = data(reportPost(discovery, owner, token, trainingBody(version, Map.of("content", answer))), 200);
                version = response.path("stateVersion").asInt();
            }
            assertTrue(response.path("canGenerateScenario").asBoolean());
            var scenario = data(reportPost(path + "/scenario", owner, token, trainingBody(version, Map.of())), 200);
            assertEquals("SCENARIO_READY", scenario.path("phase").asText());
            var restored = data(getWithCookie(path, owner), 200);
            assertEquals(scenario.path("scenario"), restored.path("scenario"));
            var started = data(reportPost(path + "/start", owner, token,
                    trainingBody(scenario.path("stateVersion").asInt(), Map.of())), 200);
            assertEquals("SIMULATION", started.path("phase").asText());
            version = started.path("stateVersion").asInt();
            assertEquals(403, client.send(request(path + "/messages", owner).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertError(reportPost(path + "/messages", owner, token,
                    trainingBody(version, Map.of("inputKind", "CHOICE", "selectedCode", "UNKNOWN"))), 400, "INVALID_REQUEST");
            for (int i = 1; i <= 2; i++) {
                var turn = data(reportPost(path + "/messages", owner, token, trainingBody(version,
                        Map.of("inputKind", "TEXT", "content", "어떤 내용을 확인하면 되나요?"))), 200);
                assertEquals(i, turn.path("turnCount").asInt());
                assertEquals(i == 1, turn.path("mockPage").isNull());
                version = turn.path("stateVersion").asInt();
            }
            var page = data(getWithCookie(path + "/mock-page", owner), 200);
            assertTrue(page.path("isSimulation").asBoolean());
            assertError(getWithCookie(path + "/mock-page", other), 404, "TRAINING_NOT_FOUND");
            var action = data(reportPost(path + "/actions", owner, token, trainingBody(version,
                    Map.of("actionCode", "MOCK_PAGE_OPEN", "actionMeta",
                            Map.of("templateCode", "BANK_NOTICE", "screenCode", "LOGIN_CHECK")))), 200);
            assertTrue(action.path("recorded").asBoolean());
            var choice = data(reportPost(path + "/messages", owner, token, trainingBody(action.path("stateVersion").asInt(),
                    Map.of("inputKind", "CHOICE", "selectedCode", "OFFICIAL_CHANNEL_CHECK"))), 200);
            assertTrue(choice.path("shouldFinish").asBoolean());
            assertTrue(data(getWithCookie(path, owner), 200).path("shouldFinish").asBoolean());
            var finish = trainingBody(choice.path("stateVersion").asInt(), Map.of("reason", "USER_FINISH"));
            var finished = data(reportPost(path + "/finish", owner, token, finish), 200);
            assertEquals("RESULT", finished.path("phase").asText());
            assertEquals(finished.path("reportId"),
                    data(reportPost(path + "/finish", owner, token, finish), 200).path("reportId"));
            var report = data(getWithCookie(path + "/report", owner), 200);
            assertEquals(finished.path("reportId"), report.path("reportId"));
            assertTrue(report.path("evaluation").path("mockPageEvaluation").path("openedPage").asBoolean());
            assertTrue(data(getWithCookie("/api/trainings/active", owner), 200).isNull());
        } finally {
            jdbc.update("delete from training_reports where training_id = ?", id);
            jdbc.update("delete from training_messages where training_id = ?", id);
            jdbc.update("delete from training_sessions where id = ?", id);
        }
    }

    private Map<String, Object> trainingBody(int version, Map<String, Object> fields) {
        var body = new java.util.HashMap<>(fields);
        body.put("stateVersion", version);
        body.put("requestId", UUID.randomUUID());
        return body;
    }

    @Test
    void guidesExposeOnlyReviewedEntriesWithoutLogin() throws Exception {
        var types = data(getWithCookie("/api/guides/fraud-types", null), 200);
        assertEquals(1, types.size());
        assertEquals("TEST_TYPE", types.get(0).path("code").asText());
        assertFalse(types.get(0).has("references"));
        assertTrue(data(getWithCookie("/api/guides/fraud-types/TEST_TYPE", null), 200).has("references"));
        assertError(getWithCookie("/api/guides/fraud-types/HIDDEN", null), 404, "GUIDE_NOT_FOUND");
        assertEquals(1, data(getWithCookie("/api/guides/response-cards?codes=TEST_CARD,OTHER", null), 200).size());
        assertTrue(data(getWithCookie("/api/guides/response-cards?codes=OTHER", null), 200).isEmpty());
        assertEquals("TEST_ORG", data(getWithCookie("/api/guides/institutions", null), 200).get(0).path("code").asText());
    }

    @Test
    void reportsKeepOwnershipAndRemoveRawMessagesWhenSaved() throws Exception {
        String owner = activeCookie(address());
        String other = activeCookie(address());
        var token = data(getWithCookie("/api/auth/csrf", owner), 200);
        var trainingIds = new ArrayList<Long>();
        String rawText = "테스트용 훈련 답변";
        try {
            long first = completeTraining(owner, token, rawText, trainingIds);
            String firstPath = "/api/trainings/" + first + "/report";
            var result = data(getWithCookie(firstPath, owner), 200);
            assertEquals("SECONDHAND_TRADE", result.path("fraudTypeCode").asText());
            assertFalse(result.path("saved").asBoolean());
            assertFalse(result.toString().contains(rawText));
            assertError(getWithCookie(firstPath, other), 404, "REPORT_NOT_FOUND");
            assertError(getWithCookie(firstPath, null), 401, "LOGIN_REQUIRED");

            String firstReport = "/api/reports/" + result.path("reportId").asLong();
            assertError(getWithCookie(firstReport, owner), 404, "REPORT_NOT_FOUND");
            verifyReportPdf(firstReport, owner, rawText);
            assertError(getWithCookie(firstReport + "/pdf", other), 404, "REPORT_NOT_FOUND");
            assertEquals(403, client.send(request(firstReport + "/save", owner)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            jdbc.update("update training_sessions set flow_state_json = jsonb_build_object('copy', cast(? as text)) where id = ?",
                    rawText, first);
            var saved = data(reportPost(firstReport + "/save", owner, token, null), 200);
            assertEquals(saved.path("savedAt"), data(reportPost(firstReport + "/save", owner, token, null), 200).path("savedAt"));
            assertEquals(0, jdbc.queryForObject("select count(*) from training_messages where training_id = ?", Integer.class, first));
            assertFalse(jdbc.queryForObject("select flow_state_json::text from training_sessions where id = ?",
                    String.class, first).contains(rawText));
            assertTrue(data(getWithCookie(firstReport, owner), 200).path("saved").asBoolean());

            long second = completeTraining(owner, token, rawText, trainingIds);
            long secondReport = data(getWithCookie("/api/trainings/" + second + "/report", owner), 200).path("reportId").asLong();
            data(reportPost("/api/reports/" + secondReport + "/save", owner, token, null), 200);
            var page = data(getWithCookie("/api/reports?size=1", owner), 200);
            assertTrue(page.path("hasNext").asBoolean());
            assertEquals(secondReport, page.path("items").get(0).path("reportId").asLong());
            var next = data(getWithCookie("/api/reports?size=1&cursor=" + page.path("nextCursor").asText(), owner), 200);
            assertFalse(next.path("hasNext").asBoolean());
            assertEquals(result.path("reportId"), next.path("items").get(0).path("reportId"));
            assertTrue(data(getWithCookie("/api/reports", other), 200).path("items").isEmpty());
        } finally {
            for (long id : trainingIds) {
                jdbc.update("delete from training_reports where training_id = ?", id);
                jdbc.update("delete from training_messages where training_id = ?", id);
                jdbc.update("delete from training_sessions where id = ?", id);
            }
        }
    }

    private long completeTraining(String cookie, JsonNode token, String rawText, List<Long> createdIds) throws Exception {
        String path = "/api/training/sessions";
        var started = data(reportPost(path, cookie, token, null), 201);
        long id = started.path("sessionId").asLong();
        createdIds.add(id);
        String turns = path + "/" + id + "/turns";
        // The preserved discovery workflow collects facts before entering simulation.
        JsonNode discovery = started;
        for (String answer : List.of(rawText, "중고거래 판매자예요", "문자로 연락했어요")) {
            discovery = data(reportPost(turns, cookie, token, Map.of("stateVersion", discovery.path("stateVersion").asInt(),
                    "requestId", UUID.randomUUID(), "message", answer)), 200);
        }
        assertEquals("SIMULATION", discovery.path("phase").asText());
        var ended = data(reportPost(turns, cookie, token, Map.of("stateVersion", discovery.path("stateVersion").asInt(),
                "requestId", UUID.randomUUID(), "message", "연락을 끊고 직접 확인할게요")), 200);
        assertTrue(ended.path("finished").asBoolean());
        return id;
    }

    private void verifyReportPdf(String path, String cookie, String rawText) throws Exception {
        var response = client.send(request(path + "/pdf", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("application/pdf", response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("training-report.pdf"));
        try (var pdf = Loader.loadPDF(response.body())) {
            String extracted = new PDFTextStripper().getText(pdf);
            assertTrue(extracted.contains("종합 평가"));
            assertFalse(extracted.contains(rawText));
        }
    }

    private HttpResponse<String> reportPost(String path, String cookie, JsonNode token, Object body) throws Exception {
        var request = request(path, cookie).header(token.path("headerName").asText(), token.path("token").asText());
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        var payload = json.readTree(response.body());
        return payload.has("data") ? payload.path("data") : payload;
    }

    private HttpResponse<String> session(String cookie) throws Exception {
        return getWithCookie("/api/auth/session", cookie);
    }

    private HttpResponse<String> getWithCookie(String path, String cookie) throws Exception {
        return client.send(request(path, cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String cookie) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path)).timeout(Duration.ofSeconds(20));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return request;
    }

    @Test
    void rejectsInvalidInputsWithoutSendingMail() throws Exception {
        String email = address();
        assertError(post("/signup", Map.of("email", "not-an-email", "password", PASSWORD)), 400, "INVALID_REQUEST");
        assertError(post("/signup", Map.of("email", email, "password", "short1")), 400, "INVALID_REQUEST");
        assertError(post("/signup", Map.of("email", email, "password", "가".repeat(24) + "A1")), 400, "INVALID_REQUEST");
        assertError(post("/signup", Map.of("email", email, "password", PASSWORD, "gender", "INVALID")), 400, "INVALID_REQUEST");
        assertError(verifyCode(email, "abc"), 400, "INVALID_REQUEST");
        assertFalse(users.existsByEmail(email));
        verifyNoInteractions(mail);
    }

    @Test
    void resendRevokesOldHashAndEnforcesCooldownAndWindow() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        String oldHash = redis.opsForValue().get(key(email, "code"));
        assertError(resend(email), 429, "RATE_LIMIT_EXCEEDED");
        assertEquals(oldHash, redis.opsForValue().get(key(email, "code")));
        redis.delete(key(email, "cooldown"));
        assertEquals(200, resend(email).statusCode());
        String newHash = redis.opsForValue().get(key(email, "code"));
        assertNotEquals(oldHash, newHash);
        codes.removeMatching(email, oldHash); // 오래된 발송 실패 정리가 새 코드를 지우면 안 돼요.
        assertEquals(newHash, redis.opsForValue().get(key(email, "code")));
        redis.delete(key(email, "cooldown"));
        assertEquals(200, resend(email).statusCode());
        redis.delete(key(email, "cooldown"));
        assertError(resend(email), 429, "RATE_LIMIT_EXCEEDED");
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
    }

    @Test
    void wrongCodeAttemptsRemainLimitedAfterResendAndExpiredCodeFails() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        String wrong = delivered.get(email).equals("000000") ? "999999" : "000000";
        for (int i = 0; i < 3; i++) assertError(verifyCode(email, wrong), 400, "INVALID_VERIFICATION_CODE");
        assertError(verifyCode(email, delivered.get(email)), 429, "RATE_LIMIT_EXCEEDED");
        redis.delete(key(email, "cooldown"));
        assertEquals(200, resend(email).statusCode());
        wrong = delivered.get(email).equals("000000") ? "999999" : "000000";
        assertError(verifyCode(email, wrong), 400, "INVALID_VERIFICATION_CODE");
        assertError(verifyCode(email, delivered.get(email)), 429, "RATE_LIMIT_EXCEEDED");
        assertEquals(UserStatus.PENDING, users.findByEmail(email).orElseThrow().getStatus());
        redis.expire(key(email, "code"), Duration.ZERO);
        assertError(verifyCode(email, delivered.get(email)), 400, "VERIFICATION_CODE_EXPIRED");
    }

    @Test
    void resendHasSameResponseForUnknownAndActiveUsers() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
        redis.delete(key(email, "cooldown"));
        String unknown = address();
        var activeResponse = resend(email);
        var unknownResponse = resend(unknown);
        assertEquals(200, activeResponse.statusCode());
        assertEquals(200, unknownResponse.statusCode());
        assertEquals(activeResponse.body(), unknownResponse.body());
        verify(mail, times(1)).send(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void mailFailureLeavesPendingUserAndAllowsResendRecovery() throws Exception {
        String email = address();
        doThrow(new ApiException(ErrorCode.MAIL_SERVICE_UNAVAILABLE)).when(mail)
                .send(eq(email), anyString(), any(Duration.class));
        assertError(signup(email), 503, "MAIL_SERVICE_UNAVAILABLE");
        assertEquals(UserStatus.PENDING, users.findByEmail(email).orElseThrow().getStatus());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(email, "code"))));
        doAnswer(invocation -> { delivered.put(email, invocation.getArgument(1)); return null; })
                .when(mail).send(eq(email), anyString(), any(Duration.class));
        redis.delete(key(email, "cooldown"));
        assertEquals(200, resend(email).statusCode());
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
    }

    @Test
    void concurrentVerificationsOnlyActivateOnce() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        String code = delivered.get(email);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> verifyCode(email, code));
            var second = executor.submit(() -> verifyCode(email, code));
            List<Integer> statuses = new ArrayList<>(List.of(first.get().statusCode(), second.get().statusCode()));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 400), statuses);
        }
        assertEquals(UserStatus.ACTIVE, users.findByEmail(email).orElseThrow().getStatus());
    }

    @Test
    void concurrentSignupCreatesOneUserAndOneEmail() throws Exception {
        String email = address();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> signup(email));
            var second = executor.submit(() -> signup(email));
            List<Integer> statuses = List.of(first.get().statusCode(), second.get().statusCode());
            assertEquals(1, statuses.stream().filter(s -> s == 201).count());
            assertTrue(statuses.stream().allMatch(s -> s == 201 || s == 409 || s == 429));
        }
        assertTrue(users.existsByEmail(email));
        verify(mail, times(1)).send(eq(email), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureDoesNotActivateUserAndCanBeRetried() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        doThrow(new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE)).when(codes).beginAttempt(email);
        assertError(verifyCode(email, delivered.get(email)), 503, "AUTH_SERVICE_UNAVAILABLE");
        assertEquals(UserStatus.PENDING, users.findByEmail(email).orElseThrow().getStatus());
        doCallRealMethod().when(codes).beginAttempt(email);
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
    }

    @Test
    void cleanupFailureAfterCommitCannotMakeCodeReusable() throws Exception {
        String email = address();
        assertEquals(201, signup(email).statusCode());
        doThrow(new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE)).when(codes).removeMatching(eq(email), anyString());
        assertEquals(200, verifyCode(email, delivered.get(email)).statusCode());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(email, "code"))));
        assertEquals(UserStatus.ACTIVE, users.findByEmail(email).orElseThrow().getStatus());
        assertError(verifyCode(email, delivered.get(email)), 400, "VERIFICATION_CODE_EXPIRED");
    }

    private String address() {
        String email = "auth-test-" + UUID.randomUUID() + "@example.com";
        addresses.add(email);
        return email;
    }

    private String key(String email, String suffix) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(email.getBytes(StandardCharsets.UTF_8)));
        return NAMESPACE + ":{" + digest + "}:" + suffix;
    }

    private HttpResponse<String> signup(String email) throws Exception {
        return post("/signup", Map.of("email", email, "password", PASSWORD, "gender", "MALE"));
    }
    private HttpResponse<String> resend(String email) throws Exception {
        return post("/email/resend", Map.of("email", email));
    }
    private HttpResponse<String> verifyCode(String email, String code) throws Exception {
        return post("/email/verify", Map.of("email", email, "code", code));
    }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/auth" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("code").asText());
    }
}
