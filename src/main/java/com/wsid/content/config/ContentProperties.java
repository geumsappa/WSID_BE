package com.wsid.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "wsid.content")
public record ContentProperties(
        @DefaultValue("classpath*:/content/fraud-types/") String fraudTypesLocation) {
    public ContentProperties {
        if (fraudTypesLocation == null || !fraudTypesLocation.matches("classpath\\*?:/(?:[A-Za-z0-9_-]+/)+")) {
            throw new IllegalArgumentException("유형 리소스 위치는 클래스패스 디렉토리여야 해요.");
        }
    }
}
