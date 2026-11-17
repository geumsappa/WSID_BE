package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** AI-05 응답 data. 저장 전 원문 복사·행동 근거 대조는 후속 검증 계층에서 수행해야 해요. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrainingEvaluateResult(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<Object> turnFeedback,
        MockPageEvaluation mockPageEvaluation,
        List<String> recommendations) {

    public static final int MAX_FEEDBACK_LENGTH = 1_000;
    public static final int MAX_ITEMS = 10;
    public static final int MAX_ITEM_LENGTH = 500;

    public TrainingEvaluateResult {
        if (overallFeedback == null || overallFeedback.isBlank()
                || overallFeedback.length() > MAX_FEEDBACK_LENGTH
                || turnFeedback == null || !turnFeedback.isEmpty() || mockPageEvaluation == null) {
            throw new IllegalArgumentException("평가 결과 형식이 올바르지 않아요.");
        }
        strengths = checkedItems(strengths);
        improvements = checkedItems(improvements);
        recommendations = checkedItems(recommendations);
        // 턴별 피드백은 1차에서 명시적인 빈 배열만 지원해요.
        turnFeedback = List.of();
    }

    private static List<String> checkedItems(List<String> items) {
        if (items == null || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("평가 항목 목록이 올바르지 않아요.");
        }
        for (String item : items) {
            if (item == null || item.isBlank() || item.length() > MAX_ITEM_LENGTH) {
                throw new IllegalArgumentException("평가 항목 내용이 올바르지 않아요.");
            }
        }
        return List.copyOf(items);
    }

    /** boolean 누락·타입 오류는 HTTP의 JsonNode 단계에서 먼저 검사해요. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MockPageEvaluation(boolean openedPage, boolean attemptedMockLogin, boolean checkedOfficialChannel) {}

    @Override
    public String toString() {
        return "TrainingEvaluateResult[feedbackLength=" + overallFeedback.length()
                + ", strengths=" + strengths.size() + ", improvements=" + improvements.size()
                + ", recommendations=" + recommendations.size() + ", turnFeedback=" + turnFeedback.size() + "]";
    }
}
