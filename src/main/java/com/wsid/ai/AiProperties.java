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
        Duration evaluationReadTimeout,
        Duration assistReadTimeout,
        Duration ocrReadTimeout,
        Duration discoveryReadTimeout) {

    @ConstructorBinding
    public AiProperties {
        if (discoveryReadTimeout == null) discoveryReadTimeout = readTimeout;
        if (discoveryReadTimeout == null || discoveryReadTimeout.isZero() || discoveryReadTimeout.isNegative()) {
            throw new IllegalArgumentException("상황 파악 응답 제한은 0보다 커야 해요.");
        }
        if (scenarioReadTimeout == null) scenarioReadTimeout = readTimeout;
        if (scenarioReadTimeout == null || scenarioReadTimeout.isZero() || scenarioReadTimeout.isNegative()) {
            throw new IllegalArgumentException("시나리오 응답 제한은 0보다 커야 해요.");
        }
        if (evaluationReadTimeout == null) evaluationReadTimeout = readTimeout;
        if (evaluationReadTimeout == null || evaluationReadTimeout.isZero() || evaluationReadTimeout.isNegative()) {
            throw new IllegalArgumentException("평가 응답 제한은 0보다 커야 해요.");
        }
        if (assistReadTimeout == null) assistReadTimeout = readTimeout;
        if (assistReadTimeout == null || assistReadTimeout.isZero() || assistReadTimeout.isNegative()) {
            throw new IllegalArgumentException("상담 assist 응답 제한은 0보다 커야 해요.");
        }
        // AI-02는 OCR(최대 15초)과 사진 분류(3초)를 합쳐 쓰므로 기본 응답 제한보다 길게 둬요.
        if (ocrReadTimeout == null) ocrReadTimeout = Duration.ofSeconds(30);
        if (ocrReadTimeout.isZero() || ocrReadTimeout.isNegative()) {
            throw new IllegalArgumentException("OCR 응답 제한은 0보다 커야 해요.");
        }
    }

    public AiProperties(String mode, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
            Duration scenarioReadTimeout, Duration evaluationReadTimeout, Duration assistReadTimeout, Duration ocrReadTimeout) {
        this(mode, baseUrl, apiKey, connectTimeout, readTimeout, scenarioReadTimeout, evaluationReadTimeout,
                assistReadTimeout, ocrReadTimeout, null);
    }

    public AiProperties(String mode, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
            Duration scenarioReadTimeout, Duration evaluationReadTimeout, Duration assistReadTimeout) {
        this(mode, baseUrl, apiKey, connectTimeout, readTimeout, scenarioReadTimeout, evaluationReadTimeout,
                assistReadTimeout, null);
    }

    public AiProperties(String mode, String baseUrl, String apiKey, Duration connectTimeout,
            Duration readTimeout, Duration scenarioReadTimeout, Duration evaluationReadTimeout) {
        this(mode, baseUrl, apiKey, connectTimeout, readTimeout, scenarioReadTimeout, evaluationReadTimeout, readTimeout);
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
