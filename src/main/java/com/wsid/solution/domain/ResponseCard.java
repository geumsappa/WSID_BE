package com.wsid.solution.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 검토가 끝난 대응 카드 중 상담에 필요한 부분.
 * 형식은 LSH GuideCatalog의 Card(code·title·priority·institutionCodes)에 SOL용 version을 더한 것이에요.
 */
public record ResponseCard(String code, int version, CardPriority priority, String title, List<String> institutionCodes) {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    public ResponseCard {
        if (code == null || !CODE.matcher(code).matches() || version < 1 || priority == null
                || title == null || title.isBlank() || institutionCodes == null
                || institutionCodes.stream().anyMatch(item -> item == null || !CODE.matcher(item).matches())) {
            throw new IllegalArgumentException("대응 카드 형식이 올바르지 않아요.");
        }
        title = title.strip();
        institutionCodes = List.copyOf(institutionCodes);
    }
}
