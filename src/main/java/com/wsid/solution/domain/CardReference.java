package com.wsid.solution.domain;

import java.util.regex.Pattern;

/** card_codes_json의 한 항목. 화면에는 code로 현재 검토 카드를 다시 조회하고, version은 기록용이에요. */
public record CardReference(String code, int version) {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    public CardReference {
        if (code == null || !CODE.matcher(code).matches() || version < 1) {
            throw new IllegalArgumentException("카드 참조 형식이 올바르지 않아요.");
        }
    }
}
