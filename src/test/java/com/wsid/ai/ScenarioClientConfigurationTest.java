package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.DiscoveryRequest;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 설정 바인딩과 실제 HTTP 통신으로 API별 응답 제한을 확인해요. */
class ScenarioClientConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class PropertiesConfiguration {}

    @Test
    void bindsScenarioTimeoutAndSelectsConfiguredClient() {
        context().withPropertyValues("wsid.ai.mode=http", "wsid.ai.scenario-read-timeout=45s")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var properties = ctx.getBean(AiProperties.class);
                    assertEquals(Duration.ofSeconds(45), properties.scenarioReadTimeout());
                    assertEquals(Duration.ofSeconds(20), properties.readTimeout());
                    assertInstanceOf(HttpAiClient.class, ctx.getBean(AiClient.class));
                });
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(Duration.ofSeconds(20), ctx.getBean(AiProperties.class).scenarioReadTimeout());
            assertInstanceOf(FakeAiClient.class, ctx.getBean(AiClient.class));
        });
    }

    @Test
    void rejectsNonPositiveScenarioTimeout() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThrows(IllegalArgumentException.class, () -> new AiProperties("http", "http://ai.test", "key",
                    Duration.ofSeconds(2), Duration.ofSeconds(20), timeout));
        }
    }

    @Test
    void scenarioCanTimeOutWithoutShorteningDiscoveryTimeout() throws IOException {
        verifyIndependentTimeouts(Duration.ofSeconds(2), Duration.ofMillis(100), false);
    }

    @Test
    void scenarioCanWaitLongerWithoutExtendingDiscoveryTimeout() throws IOException {
        verifyIndependentTimeouts(Duration.ofMillis(100), Duration.ofSeconds(2), true);
    }

    private void verifyIndependentTimeouts(Duration normalTimeout, Duration scenarioTimeout,
            boolean scenarioSucceeds) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedId = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext(HttpAiClient.SCENARIO_PATH, exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            delayedResponse(exchange, """
                    {"success":true,"data":{"scenario":{"title":"훈련","description":"가상 상황"},
                    "mockPageTemplateCode":"BANK_NOTICE"}}""");
        });
        server.createContext("/internal/ai/discovery/next", exchange -> {
            exchange.getRequestBody().readAllBytes();
            delayedResponse(exchange, """
                    {"success":true,"data":{"facts":{},"nextQuestion":"어떤 상황인가요?","canGenerateScenario":false}}""");
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AiClient client = new AiClientConfig().aiClient(new AiProperties("http", baseUrl, "test-key",
                    Duration.ofSeconds(1), normalTimeout, scenarioTimeout));
            UUID id = UUID.randomUUID();
            var scenario = new ScenarioGenerateRequest(Map.of("activity", "중고거래"), "SECONDHAND_TRADE", id);
            var discovery = new DiscoveryRequest(List.of(), Map.of(), "질문", UUID.randomUUID());
            if (scenarioSucceeds) {
                assertNotNull(client.generateScenario(scenario));
                assertThrows(AiUnavailableException.class, () -> client.suggestDiscovery(discovery));
            } else {
                assertThrows(AiUnavailableException.class, () -> client.generateScenario(scenario));
                assertNotNull(client.suggestDiscovery(discovery));
            }
            assertEquals("test-key", receivedKey.get());
            assertEquals(id.toString(), receivedId.get());
            assertTrue(receivedBody.get().contains("중고거래"));
            assertFalse(receivedBody.get().contains("requestId"));
        } finally {
            server.stop(0);
            executor.close();
        }
    }

    private void delayedResponse(HttpExchange exchange, String body) throws IOException {
        try {
            Thread.sleep(400);
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

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class, AiClientConfig.class)
                .withPropertyValues("wsid.ai.mode=fake", "wsid.ai.base-url=http://ai.test", "wsid.ai.api-key=test-key",
                        "wsid.ai.connect-timeout=2s", "wsid.ai.read-timeout=20s");
    }
}
