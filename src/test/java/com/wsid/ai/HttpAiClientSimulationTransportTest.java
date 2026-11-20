package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.ScenarioGenerateRequest;
import com.wsid.ai.dto.ScenarioGenerateResult.Scenario;
import com.wsid.ai.dto.SimulationReplyRequest;
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

/** 실제 HTTP로 AI-04가 AI-03 전용 응답 제한을 사용하지 않는지 확인해요. */
class HttpAiClientSimulationTransportTest {

    @Test
    void shorterScenarioTimeoutDoesNotShortenSimulationTimeout() throws IOException {
        verifyTransport(Duration.ofSeconds(2), Duration.ofMillis(200), true);
    }

    @Test
    void longerScenarioTimeoutDoesNotExtendSimulationTimeout() throws IOException {
        verifyTransport(Duration.ofMillis(200), Duration.ofSeconds(2), false);
    }

    private void verifyTransport(Duration turnTimeout, Duration scenarioTimeout,
            boolean simulationSucceeds) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedId = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext(HttpAiClient.SIMULATION_PATH, exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            delayedResponse(exchange, """
                    {"success":true,"data":{"reply":"가상 대사","recognizedActions":[],
                    "shouldFinish":false,"mockPage":null}}""");
        });
        server.createContext(HttpAiClient.SCENARIO_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            delayedResponse(exchange, """
                    {"success":true,"data":{"scenario":{"title":"훈련","description":"가상 상황"},
                    "mockPageTemplateCode":"BANK_NOTICE"}}""");
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AiClient client = new AiClientConfig().aiClient(new AiProperties("http", baseUrl, "test-key",
                    Duration.ofSeconds(1), turnTimeout, scenarioTimeout));
            UUID id = UUID.randomUUID();
            var reply = new SimulationReplyRequest(new Scenario("훈련", "가상 상황"),
                    List.of(), "공식 채널을 확인하겠습니다.", 2, id);
            var scenario = new ScenarioGenerateRequest(Map.of("activity", "중고거래"), "SECONDHAND_TRADE", UUID.randomUUID());
            if (simulationSucceeds) {
                assertEquals("가상 대사", client.replySimulation(reply).reply());
                var failure = assertThrows(AiUnavailableException.class, () -> client.generateScenario(scenario));
                assertNull(failure.getCause());
            } else {
                var failure = assertThrows(AiUnavailableException.class, () -> client.replySimulation(reply));
                assertNull(failure.getCause());
                assertNotNull(client.generateScenario(scenario));
            }
            assertEquals("test-key", receivedKey.get());
            assertEquals(id.toString(), receivedId.get());
            assertTrue(receivedBody.get().contains("공식 채널"));
            assertFalse(receivedBody.get().contains("requestId"));
            assertFalse(receivedBody.get().contains(id.toString()));
        } finally {
            server.stop(0);
            executor.close();
        }
    }

    private void delayedResponse(HttpExchange exchange, String body) throws IOException {
        try {
            Thread.sleep(500);
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
}
