package com.wsid.ai.dto;

import java.util.regex.Pattern;

/** 승인된 설명용 문단의 전송 형식. 실제 출처·승인·버전 확인은 호출자 책임이에요. */
public record VerifiedReference(String code, String title, String excerpt) {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_EXCERPT_LENGTH = 2_000;
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,100}");

    public VerifiedReference {
        if (!isValidCode(code) || title == null || title.isBlank() || title.length() > MAX_TITLE_LENGTH
                || excerpt == null || excerpt.isBlank() || excerpt.length() > MAX_EXCERPT_LENGTH) {
            throw new IllegalArgumentException("설명 근거 형식이 올바르지 않아요.");
        }
    }

    public static boolean isValidCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    @Override
    public String toString() {
        return "VerifiedReference[titleLength=" + title.length() + ", excerptLength=" + excerpt.length() + "]";
    }
}
