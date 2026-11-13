package com.wsid.ai;

import com.wsid.ai.dto.TrainingTurnRequest;
import com.wsid.ai.dto.TrainingTurnSuggestion;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** AI 서비스(FastAPI)를 HTTP로 호출해요. 타임아웃이 나면 AiUnavailableException으로 바꿔 던져요. */
public class HttpAiClient implements AiClient {

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
}
