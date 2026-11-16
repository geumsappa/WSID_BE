package com.wsid.content.model;

import java.net.URI;
import java.time.LocalDate;
import java.util.regex.Pattern;

/** 검증한 유형 정보. 출처 형식 검사와 실제 콘텐츠 승인은 별도예요. */
public record FraudType(String code, String name, String description, int displayOrder,
                        boolean reviewed, URI source, LocalDate checkedAt) {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    public FraudType {
        if (code == null || !CODE.matcher(code).matches() || name == null || description == null
                || displayOrder < 0 || source == null || !"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null || checkedAt == null) {
            throw new IllegalArgumentException("사기 유형의 코드나 관리 정보가 올바르지 않아요.");
        }
        name = name.strip();
        description = description.strip();
        if (name.isBlank() || name.length() > 100 || description.isBlank() || description.length() > 1_000) {
            throw new IllegalArgumentException("사기 유형의 이름이나 설명이 올바르지 않아요.");
        }
    }
}
