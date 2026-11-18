package com.wsid.ai;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Spring의 공개 훈련 API와 팀 내부 AI 계약 사이의 HTTP 경계. */
@Component
public class TrainingAiGateway {
    private final AiProperties properties;
    private final RestClient client;

    public TrainingAiGateway(AiProperties properties) {
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                // Uvicorn serves HTTP/1.1; avoid an h2c upgrade on JSON POST requests.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeader("X-Internal-Api-Key", properties.apiKey()).build();
    }

    public boolean isMock() {
        return "fake".equals(properties.mode());
    }

    public JsonNode request(String path, Object body) {
        try {
            JsonNode response = client.post().uri("/internal/ai" + path).body(body).retrieve().body(JsonNode.class);
            if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject()) {
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            return response.path("data");
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value() == 429
                    ? ErrorCode.AI_RATE_LIMIT_EXCEEDED : ErrorCode.AI_SERVICE_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }
}
