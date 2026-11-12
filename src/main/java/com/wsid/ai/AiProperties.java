package com.wsid.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wsid.ai")
public record AiProperties(
        String mode,
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout) {
}
