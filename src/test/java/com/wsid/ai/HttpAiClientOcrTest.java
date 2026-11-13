package com.wsid.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI-02 HTTP 어댑터를 JDK 내장 서버로 확인해요. */
class HttpAiClientOcrTest {

    private HttpServer server;
    private HttpAiClient client;
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedApiKey = new AtomicReference<>();
    private volatile int status;
    private volatile String responseBody;
    private volatile String responseContentType = "application/json";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HttpAiClient.OCR_PATH, exchange -> {
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new HttpAiClient(new AiProperties("http", baseUrl, "test-key", Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 성공하면_data를_꺼내고_images_파트로_보낸다() {
        respond(200, """
                {"success":true,"data":{"recognizedText":"거래 관련 안내","usable":true}}""");

        OcrExtractResult result = client.extractText(List.of(
                new OcrImage("a.png", "image/png", new byte[] {1, 2, 3}),
                new OcrImage(null, null, new byte[] {4})));

        assertThat(result.recognizedText()).isEqualTo("거래 관련 안내");
        assertThat(result.usable()).isTrue();
        assertThat(result.textUsable()).isTrue();
        assertThat(receivedContentType.get()).startsWith("multipart/form-data");
        assertThat(receivedApiKey.get()).isEqualTo("test-key");
        assertThat(receivedBody.get())
                .contains("name=\"images\"; filename=\"a.png\"")
                .contains("name=\"images\"; filename=\"image-2\"")
                .contains("Content-Type: image/png");
    }

    @Test
    void 민감정보가_걸리면_usable_false를_그대로_전달한다() {
        respond(200, """
                {"success":true,"data":{"recognizedText":"","usable":false}}""");

        OcrExtractResult result = client.extractText(List.of(new OcrImage("a.png", "image/png", new byte[] {1})));

        assertThat(result.usable()).isFalse();
        assertThat(result.textUsable()).isFalse();
    }

    @Test
    void OCR_FAILED면_실패_결과를_돌려준다() {
        respond(422, """
                {"success":false,"error":{"code":"OCR_FAILED","message":"문자를 읽지 못했어요."}}""");

        OcrExtractResult result = client.extractText(List.of(new OcrImage("a.png", "image/png", new byte[] {1})));

        assertThat(result).isEqualTo(OcrExtractResult.failed());
    }

    @Test
    void 다른_오류_코드는_AI_사용불가로_바꾼다() {
        respond(500, """
                {"success":false,"error":{"code":"INTERNAL_ERROR","message":"x"}}""");

        assertThatThrownBy(() -> client.extractText(List.of(new OcrImage("a.png", "image/png", new byte[] {1}))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void JSON이_아닌_오류_본문도_AI_사용불가로_바꾼다() {
        responseContentType = "text/html";
        respond(502, "<html>Bad Gateway</html>");

        assertThatThrownBy(() -> client.extractText(List.of(new OcrImage("a.png", "image/png", new byte[] {1}))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 성공인데_data가_없으면_AI_사용불가로_바꾼다() {
        respond(200, """
                {"success":true}""");

        assertThatThrownBy(() -> client.extractText(List.of(new OcrImage("a.png", "image/png", new byte[] {1}))))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 인식한_문자는_toString에_남기지_않는다() {
        assertThat(new OcrExtractResult("010-1234-5678", true).toString()).doesNotContain("010");
    }

    private void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }
}
