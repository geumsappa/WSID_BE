package com.wsid.ai;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Spring의 공개 훈련 API와 팀 내부 AI 계약 사이의 HTTP 경계. */
@Component
public class TrainingAiGateway {
    private static final Logger log = LoggerFactory.getLogger(TrainingAiGateway.class);
    private final AiProperties properties;
    private final RestClient client;
    private final RestClient scenarioClient;
    private final RestClient evaluationClient;

    public TrainingAiGateway(AiProperties properties) {
        this.properties = properties;
        client = client(properties.readTimeout());
        scenarioClient = client(properties.scenarioReadTimeout());
        evaluationClient = client(properties.evaluationReadTimeout());
    }

    private RestClient client(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
    }

    public boolean isMock() {
        return "fake".equals(properties.mode());
    }

    public JsonNode request(String path, Object body) {
        try {
            RestClient selected = "/training/evaluate".equals(path) ? evaluationClient
                    : "/scenarios/generate".equals(path) ? scenarioClient : client;
            JsonNode response = selected.post().uri("/internal/ai" + path).body(body).retrieve().body(JsonNode.class);
            if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject()) {
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            return response.path("data");
        } catch (RestClientResponseException e) {
            log.warn("Training AI rejected request: path={}, status={}", path, e.getStatusCode().value());
            throw new ApiException(e.getStatusCode().value() == 429
                    ? ErrorCode.AI_RATE_LIMIT_EXCEEDED : ErrorCode.AI_SERVICE_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("Training AI transport failed: path={}, type={}", path, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }
}
