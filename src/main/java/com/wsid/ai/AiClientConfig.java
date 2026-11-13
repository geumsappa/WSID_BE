package com.wsid.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiClientConfig {

    /** wsid.ai.mode=http 이면 실제 AI 서비스를, 아니면 가짜 구현을 써요. */
    @Bean
    AiClient aiClient(AiProperties properties) {
        if ("http".equals(properties.mode())) {
            return new HttpAiClient(properties, HttpAiClient.configuredBuilder(properties));
        }
        return new FakeAiClient();
    }
}
