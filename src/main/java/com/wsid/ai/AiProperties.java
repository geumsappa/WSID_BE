package com.wsid.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "wsid.ai")
public record AiProperties(
        String mode,
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration scenarioReadTimeout,
        Duration evaluationReadTimeout) {

    @ConstructorBinding
    public AiProperties {
        if (scenarioReadTimeout == null) scenarioReadTimeout = readTimeout;
        if (scenarioReadTimeout == null || scenarioReadTimeout.isZero() || scenarioReadTimeout.isNegative()) {
            throw new IllegalArgumentException("시나리오 응답 제한은 0보다 커야 해요.");
        }
        if (evaluationReadTimeout == null) evaluationReadTimeout = readTimeout;
        if (evaluationReadTimeout == null || evaluationReadTimeout.isZero() || evaluationReadTimeout.isNegative()) {
            throw new IllegalArgumentException("평가 응답 제한은 0보다 커야 해요.");
        }
    }

    /** 기존 호출·테스트의 설정 방식은 유지해요. */
    public AiProperties(String mode, String baseUrl, String apiKey,
            Duration connectTimeout, Duration readTimeout) {
        this(mode, baseUrl, apiKey, connectTimeout, readTimeout, readTimeout, readTimeout);
    }

    /** 시나리오 전용 제한을 지정하던 기존 호출도 그대로 지원해요. */
    public AiProperties(String mode, String baseUrl, String apiKey,
            Duration connectTimeout, Duration readTimeout, Duration scenarioReadTimeout) {
        this(mode, baseUrl, apiKey, connectTimeout, readTimeout, scenarioReadTimeout, readTimeout);
    }
}
