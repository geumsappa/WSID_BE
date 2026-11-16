package com.wsid.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wsid.auth")
public record AuthProperties(String redisNamespace, Duration codeTtl, Duration resendInterval,
        Duration rateWindow, int maxSends, int maxAttemptsPerCode, int maxAttemptsPerWindow,
        boolean mailEnabled, String mailFrom) {
    public AuthProperties {
        if (redisNamespace == null || redisNamespace.isBlank()
                || codeTtl == null || codeTtl.toSeconds() < 1
                || resendInterval == null || resendInterval.toSeconds() < 1
                || rateWindow == null || rateWindow.toSeconds() < 1
                || maxSends < 1 || maxAttemptsPerCode < 1 || maxAttemptsPerWindow < 1) {
            throw new IllegalArgumentException("AUTH 인증 코드 설정을 확인해 주세요.");
        }
    }
}
