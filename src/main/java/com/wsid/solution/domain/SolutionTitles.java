package com.wsid.solution.domain;
import java.util.ArrayList;
public final class SolutionTitles {
    /** 목록 제목으로 저장하는 피해 설명 앞부분 길이(코드 포인트 기준). */
    public static final int MAX_DESCRIPTION_LENGTH = 40;
    private SolutionTitles() {}

    /**
     * 첫 피해 설명으로 목록 제목을 만들어요. 줄바꿈·연속 공백은 한 칸으로 줄이고,
     * 40자를 넘으면 자른 뒤 "…"를 붙여요. 비어 있으면 null이에요.
     * 민감정보 검사(SensitiveDescriptionGuard)를 통과한 설명에만 써요.
     */
    public static String fromDescription(String description) {
        if (description == null) return null;
        String normalized = description.replaceAll("\\s+", " ").strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) <= MAX_DESCRIPTION_LENGTH) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, MAX_DESCRIPTION_LENGTH)).stripTrailing() + "…";
    }

    /** 설명 없이 시작한 상담의 제목. 확정 사실 중 YES인 항목을 최대 두 개 조합해요. */
    public static String of(SolutionFacts facts) {
        var labels=new ArrayList<String>();
        if(facts.get(SolutionFact.TRANSFERRED_MONEY)==FactValue.YES) labels.add("송금");
        if(facts.get(SolutionFact.SHARED_INFORMATION)==FactValue.YES) labels.add("정보 제공");
        if(facts.get(SolutionFact.INSTALLED_APP)==FactValue.YES) labels.add("앱 설치");
        if(facts.get(SolutionFact.CONTACT_CONTINUES)==FactValue.YES) labels.add("연락 지속");
        return labels.isEmpty()?"피해 대응 상담":String.join("·",labels.stream().limit(2).toList())+" 상담";
    }
}
