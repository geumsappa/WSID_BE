package com.wsid.auth;

import com.wsid.WsidApplication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;

/** 실행 중인 로컬 PostgreSQL·Redis 필요. 테스트 전용 경로는 배포되지 않아요. */
@Tag("integration")
@ActiveProfiles("local")
@SpringBootTest(classes = WsidApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisSessionIntegrationTest.ProbeConfiguration.class)
class RedisSessionIntegrationTest {
    private static final String NAMESPACE = "wsid:test:session:" + UUID.randomUUID();
    private static final String PATH = "/api/auth/_session-probe";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.session.data.redis.namespace", () -> NAMESPACE);
        registry.add("spring.session.timeout", () -> "30m");
    }

    @Autowired Environment environment;
    @Autowired SessionRepository<?> sessions;
    @Autowired StringRedisTemplate redis;

    @Test
    void storesRestoresAndInvalidatesSessionThroughHttp() throws Exception {
        String base = "http://localhost:" + environment.getProperty("local.server.port") + PATH;
        try (HttpClient client = HttpClient.newHttpClient()) {
            // 조회만으로 세션을 만들지 않아요.
            var absent = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("none", absent.body());
            assertTrue(absent.headers().allValues("Set-Cookie").isEmpty());

            var created = client.send(HttpRequest.newBuilder(URI.create(base))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode());
            String id = created.body();
            try {
                String cookie = created.headers().firstValue("Set-Cookie").orElseThrow();
                assertTrue(cookie.startsWith("SESSION="));
                assertTrue(cookie.contains("HttpOnly"));
                assertTrue(cookie.contains("SameSite=Lax"));
                assertFalse(cookie.contains("Secure"), "local HTTP에서는 Secure를 해제해야 해요.");
                String requestCookie = cookie.split(";", 2)[0];

                Session stored = sessions.findById(id);
                assertNotNull(stored);
                assertEquals("persisted", stored.getAttribute("probe"));
                assertEquals(Duration.ofMinutes(30), stored.getMaxInactiveInterval());
                String key = NAMESPACE + ":sessions:" + id;
                assertTrue(Boolean.TRUE.equals(redis.hasKey(key)), "실제 Redis 저장을 확인해요.");
                Long ttl = redis.getExpire(key);
                assertNotNull(ttl);
                assertTrue(ttl > 0 && ttl <= 1800);

                var restored = client.send(HttpRequest.newBuilder(URI.create(base))
                        .header("Cookie", requestCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, restored.statusCode());
                assertEquals(id + ":persisted", restored.body());

                var invalidated = client.send(HttpRequest.newBuilder(URI.create(base + "/invalidate"))
                        .header("Cookie", requestCookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, invalidated.statusCode());
                assertTrue(invalidated.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
                assertNull(sessions.findById(id));
                assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));

                var oldCookie = client.send(HttpRequest.newBuilder(URI.create(base))
                        .header("Cookie", requestCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals("none", oldCookie.body());
            } finally {
                sessions.deleteById(id); // 이 테스트가 만든 세션만 정리해요.
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @PostMapping(PATH)
        String create(HttpServletRequest request) {
            HttpSession session = request.getSession();
            session.setAttribute("probe", "persisted");
            return session.getId();
        }

        @GetMapping(PATH)
        String read(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            return session == null ? "none" : session.getId() + ":" + session.getAttribute("probe");
        }

        @PostMapping(PATH + "/invalidate")
        String invalidate(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            return "done";
        }
    }
}
