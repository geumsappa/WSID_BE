package com.wsid.solution.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 확인한 사실 → 보여줄 카드 코드. 긴급 여부는 카드 콘텐츠의 priority로만 정해요.
 * 현재 규칙은 명세 예시(송금 YES, 앱 설치 YES) 두 개뿐이고 나머지 조합은 콘텐츠 담당 검토 후 추가해요.
 */
public final class ResponseRules {
    public static final String TRANSFER_RESPONSE = "TRANSFER_RESPONSE";
    public static final String MALICIOUS_APP_RESPONSE = "MALICIOUS_APP_RESPONSE";

    private ResponseRules() {}

    /** 규칙 순서대로 중복 없는 카드 코드. */
    public static List<String> cardCodes(SolutionFacts facts) {
        Set<String> codes = new LinkedHashSet<>();
        if (facts.get(SolutionFact.TRANSFERRED_MONEY) == FactValue.YES) codes.add(TRANSFER_RESPONSE);
        if (facts.get(SolutionFact.INSTALLED_APP) == FactValue.YES) codes.add(MALICIOUS_APP_RESPONSE);
        return List.copyOf(codes);
    }

    /**
     * 규칙이 고른 카드를 검토된 카탈로그에서 찾아 URGENT 먼저, 같은 우선순위는 규칙 순서로 정렬해요.
     * 카드가 하나라도 없으면 긴급 안내를 조용히 빠뜨리지 않도록 예외를 던져요.
     */
    public static List<ResponseCard> cards(SolutionFacts facts, ResponseCardCatalog catalog) {
        var cards = new ArrayList<ResponseCard>();
        for (String code : cardCodes(facts)) {
            cards.add(catalog.findReviewed(code).orElseThrow(() -> new MissingResponseCardException(code)));
        }
        cards.sort(Comparator.comparing(ResponseCard::priority)); // 안정 정렬이라 규칙 순서가 유지돼요
        return List.copyOf(cards);
    }

    /** 규칙이 참조하는 검토 카드가 없을 때. */
    public static class MissingResponseCardException extends RuntimeException {
        private final String code;

        public MissingResponseCardException(String code) {
            super("검토된 대응 카드가 없어요: " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
