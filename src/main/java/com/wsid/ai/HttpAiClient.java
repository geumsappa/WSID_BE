package com.wsid.ai;

import com.wsid.ai.dto.AiEnvelope;
import com.wsid.ai.dto.OcrExtractResult;
import com.wsid.ai.dto.OcrImage;
import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** AI 서비스(FastAPI)를 HTTP로 호출해요. 타임아웃이 나면 AiUnavailableException으로 바꿔 던져요. */
public class HttpAiClient implements AiClient {

    static final String OCR_PATH = "/internal/ai/ocr/extract";
    static final String OCR_FAILED = "OCR_FAILED";

    private static final ParameterizedTypeReference<AiEnvelope<OcrExtractResult>> OCR_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public HttpAiClient(AiProperties properties) {
        this(properties, configuredBuilder(properties));
    }

    // The injected builder owns the transport; tests can bind MockRestServiceServer.
    public HttpAiClient(AiProperties properties, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
    }

    public static RestClient.Builder configuredBuilder(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    @Override
    public com.wsid.ai.dto.DiscoveryResult suggestDiscovery(com.wsid.ai.dto.DiscoveryRequest request) {
        try {
            var node = restClient.post().uri("/internal/ai/discovery/next")
                    .header("X-Request-Id", request.requestId().toString()).body(request).retrieve()
                    .body(tools.jackson.databind.JsonNode.class);
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("success").asBoolean() || !node.path("data").isObject()
                    || !node.path("data").path("facts").isObject()
                    || !node.path("data").path("nextQuestion").isTextual()
                    || !node.path("data").path("canGenerateScenario").isBoolean())
                throw new AiUnavailableException("AI-01 응답 형식이 올바르지 않아요.");
            var envelope = new tools.jackson.databind.ObjectMapper().treeToValue(node, com.wsid.ai.dto.DiscoveryEnvelope.class);
            if (envelope == null || !Boolean.TRUE.equals(envelope.success()) || (envelope.data() == null || !envelope.data().isValid()))
                throw new AiUnavailableException("AI-01 응답 형식이 올바르지 않아요.");
            return envelope.data();
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            // HTTP exception messages can contain response bodies. Do not retain them.
            throw new AiUnavailableException("AI-01 호출에 실패했어요.");
        }
    }

    @Override
    public TrainingTurnSuggestion suggestTrainingTurn(TrainingTurnRequest request) {
        try {
            TrainingTurnSuggestion suggestion = restClient.post()
                    .uri("/internal/training/turn")
                    .body(request)
                    .retrieve()
                    .body(TrainingTurnSuggestion.class);
            if (suggestion == null) {
                throw new AiUnavailableException("AI 서비스가 빈 응답을 보냈어요.");
            }
            return suggestion;
        } catch (RestClientException e) {
            throw new AiUnavailableException("AI 서비스 호출에 실패했어요.", e);
        }
    }

    /**
     * AI-02. multipart/form-data의 images 파트로 이미지를 보내요.
     * 오류 상태 코드여도 본문의 error.code가 OCR_FAILED면 실패 결과로 돌려주고,
     * 그 밖의 실패는 AiUnavailableException으로 바꿔요. 본문은 로그에 남기지 않아요.
     */
    @Override
    public OcrExtractResult extractText(List<OcrImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("이미지가 한 장 이상 필요해요.");
        }
        try {
            AiEnvelope<OcrExtractResult> envelope = restClient.post()
                    .uri(OCR_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(toMultipart(images))
                    .exchange((request, response) -> {
                        try {
                            return response.bodyTo(OCR_RESPONSE);
                        } catch (RuntimeException e) {
                            // 프록시 오류 페이지처럼 JSON이 아닌 본문
                            // 예외 메시지에 응답 본문이 섞일 수 있어 원인을 남기지 않아요.
                            throw new AiUnavailableException(
                                    "AI 서비스 OCR 응답을 읽지 못했어요. status=" + response.getStatusCode().value());
                        }
                    });
            return unwrap(envelope);
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            // 예외 메시지에 응답 본문이 섞일 수 있어 원인을 남기지 않아요.
            throw new AiUnavailableException("AI 서비스 OCR 호출에 실패했어요.");
        }
    }

    private static OcrExtractResult unwrap(AiEnvelope<OcrExtractResult> envelope) {
        if (envelope == null) {
            throw new AiUnavailableException("AI 서비스가 빈 OCR 응답을 보냈어요.");
        }
        if (!envelope.success()) {
            if (OCR_FAILED.equals(envelope.errorCode())) {
                return OcrExtractResult.failed();
            }
            throw new AiUnavailableException("AI 서비스 OCR 처리에 실패했어요. code=" + envelope.errorCode());
        }
        if (envelope.data() == null) {
            throw new AiUnavailableException("AI 서비스 OCR 응답에 data가 없어요.");
        }
        return envelope.data();
    }

    /** reactive 의존성이 필요한 MultipartBodyBuilder 대신 파트마다 HttpEntity를 만들어요. */
    private static MultiValueMap<String, HttpEntity<?>> toMultipart(List<OcrImage> images) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        for (int i = 0; i < images.size(); i++) {
            OcrImage image = images.get(i);
            String filename = image.filename() == null || image.filename().isBlank()
                    ? "image-" + (i + 1)
                    : image.filename();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(image.contentType() == null || image.contentType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(image.contentType()));
            headers.setContentDisposition(ContentDisposition.formData().name("images").filename(filename).build());
            parts.add("images", new HttpEntity<>(new ByteArrayResource(image.content()), headers));
        }
        return parts;
    }
}
