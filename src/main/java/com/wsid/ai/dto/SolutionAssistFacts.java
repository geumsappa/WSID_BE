package com.wsid.ai.dto;

import java.util.Map;

/** assist 요청의 사실 검사. AI-06 키·값을 그대로 쓰되 네 키를 모두 보내야 해요. */
final class SolutionAssistFacts {
    private SolutionAssistFacts() {}

    static Map<String, String> requireAll(Map<String, String> facts) {
        if (facts == null || !facts.keySet().equals(SolutionExtractRequest.FACT_KEYS)) {
            throw new IllegalArgumentException("상담 사실 네 개를 모두 보내야 해요.");
        }
        facts.forEach((key, value) -> {
            if (value == null || !SolutionExtractRequest.FACT_VALUES.contains(value)) {
                throw new IllegalArgumentException("상담 사실 값이 올바르지 않아요.");
            }
        });
        return Map.copyOf(facts);
    }

    static void requireQuestion(String question) {
        if (question != null && (question.isBlank()
                || question.length() > SolutionAssistPlanRequest.MAX_QUESTION_LENGTH)) {
            throw new IllegalArgumentException("상담 질문 형식이 올바르지 않아요.");
        }
    }
}
