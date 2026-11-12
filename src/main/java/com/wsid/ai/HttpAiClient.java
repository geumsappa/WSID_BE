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
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Api-Key", properties.apiKey())
                .build();
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
