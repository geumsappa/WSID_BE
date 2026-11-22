package com.wsid.ai.dto;

import java.util.List;
import java.util.Set;

/** 사용자가 답한 조건. 질문 문장 없이 문단 코드·원문 인용·답만 저장하고 다음 assist 요청에 그대로 보내요. */
public record SolutionAssistConfirmedCondition(String conditionId, String referenceCode, String quote, String answer) {
    public static final int MAX_CONDITIONS = 10;
    private static final Set<String> ANSWERS = Set.of("YES", "NO", "UNKNOWN");

    public SolutionAssistConfirmedCondition {
        new SolutionAssistCondition(conditionId, referenceCode, quote); // id·코드·인용 검증
        if (!ANSWERS.contains(answer)) throw new IllegalArgumentException("조건 답이 올바르지 않아요.");
    }

    public static SolutionAssistConfirmedCondition of(SolutionAssistCondition condition, String answer) {
        return new SolutionAssistConfirmedCondition(condition.conditionId(), condition.referenceCode(), condition.quote(), answer);
    }

    /** null은 v1(조건 질문 비활성), 목록은 v1.2 활성. 최대 10개, conditionId 중복 불가. */
    public static List<SolutionAssistConfirmedCondition> requireList(List<SolutionAssistConfirmedCondition> conditions) {
        if (conditions == null) return null;
        if (conditions.size() > MAX_CONDITIONS || conditions.stream().anyMatch(java.util.Objects::isNull)
                || conditions.stream().map(SolutionAssistConfirmedCondition::conditionId).distinct().count() != conditions.size()) {
            throw new IllegalArgumentException("확정된 조건 목록이 올바르지 않아요.");
        }
        return List.copyOf(conditions);
    }

    @Override
    public String toString() {
        return "SolutionAssistConfirmedCondition[conditionId=" + conditionId + ", answer=" + answer + "]";
    }
}
