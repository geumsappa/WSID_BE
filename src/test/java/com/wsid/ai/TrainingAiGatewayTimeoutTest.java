package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.wsid.common.error.ApiException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TrainingAiGatewayTimeoutTest {
    @Test void evaluationUsesItsOwnTimeoutInsteadOfShortConversationTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/internal/ai", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(400);
                byte[] response = "{\"success\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
              catch (java.io.IOException ignored) { /* Expected when the short request is cancelled. */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new AiProperties("http", "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofSeconds(2),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));
            var gateway = new TrainingAiGateway(properties);
            assertTrue(gateway.request("/training/evaluate", Map.of()).isObject());
            assertTrue(gateway.request("/discovery/next", Map.of()).isObject());
            assertThrows(ApiException.class, () -> gateway.request("/simulation/reply", Map.of()));
        } finally { server.stop(0); executor.close(); }
    }
}
