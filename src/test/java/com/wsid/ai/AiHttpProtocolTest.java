package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiHttpProtocolTest {
    @Test
    void trainingGatewaySendsJsonWithoutH2cUpgrade() throws Exception {
        verify(true);
    }

    @Test
    void sharedAiClientSendsJsonWithoutH2cUpgrade() throws Exception {
        verify(false);
    }

    private void verify(boolean trainingGateway) throws Exception {
        var upgrade = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/discovery/next", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"data\":{\"nextQuestion\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upgrade.get() == null ? 200 : 422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiProperties("http", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", Duration.ofSeconds(2), Duration.ofSeconds(2));
            if (trainingGateway) {
                var result = new TrainingAiGateway(properties).request("/discovery/next", Map.of("currentInput", "test"));
                assertEquals("test", result.path("nextQuestion").asString());
            } else {
                var result = HttpAiClient.configuredBuilder(properties).baseUrl(properties.baseUrl())
                        .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build()
                        .post().uri("/internal/ai/discovery/next").body(Map.of("currentInput", "test"))
                        .retrieve().body(String.class);
                assertNotNull(result);
            }
            assertNull(upgrade.get());
            assertEquals("test-key", key.get());
            assertTrue(body.get().contains("\"currentInput\":\"test\""));
        } finally {
            server.stop(0);
        }
    }
}
