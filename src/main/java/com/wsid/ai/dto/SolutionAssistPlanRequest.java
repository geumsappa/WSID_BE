package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 상담 assist PLAN 요청(계약 초안 v1). 첫 실행은 question을, 추가 질문 뒤 재실행은 question=null과
 * 첫 PLAN의 questionIntent를 보내요. Python은 요청 사이 상태를 저장하지 않아요.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"phase", "question", "questionIntent", "facts", "askedFactKeys", "remainingClarifications", "fraudType", "history"})
public record SolutionAssistPlanRequest(
        String question, SolutionAssistIntent questionIntent, Map<String, String> facts,
        List<String> askedFactKeys, int remainingClarifications, @JsonIgnore UUID requestId, String fraudType, List<SolutionAssistHistoryTurn> history) {

    public SolutionAssistPlanRequest(String question, SolutionAssistIntent questionIntent, Map<String, String> facts,
            List<String> askedFactKeys, int remainingClarifications, UUID requestId) {
        this(question, questionIntent, facts, askedFactKeys, remainingClarifications, requestId, null, null);
    }
    public SolutionAssistPlanRequest(String question, SolutionAssistIntent questionIntent, Map<String, String> facts,
            List<String> askedFactKeys, int remainingClarifications, UUID requestId, String fraudType) {
        this(question, questionIntent, facts, askedFactKeys, remainingClarifications, requestId, fraudType, null);
    }
    public static final int MAX_QUESTION_LENGTH = 1_000;
    public static final int MAX_CLARIFICATIONS = 3;

    public SolutionAssistPlanRequest {
        SolutionAssistFacts.requireQuestion(question);
        if ((question == null && questionIntent == null) || askedFactKeys == null
                || remainingClarifications < 0 || remainingClarifications > MAX_CLARIFICATIONS || requestId == null) {
            throw new IllegalArgumentException("상담 assist PLAN 요청 형식이 올바르지 않아요.");
        }
        facts = SolutionAssistFacts.requireAll(facts);
        var keys = new HashSet<String>();
        for (String key : askedFactKeys) {
            if (key == null || !SolutionExtractRequest.FACT_KEYS.contains(key) || !keys.add(key)) {
                throw new IllegalArgumentException("이미 물은 사실 키가 올바르지 않거나 중복돼요.");
            }
        }
        askedFactKeys = List.copyOf(askedFactKeys);
        fraudType = SolutionFraudType.require(fraudType);
        if (history != null) {
            if (history.size() > 10 || history.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("대화 이력은 최대 10개이고 빈 항목을 넣을 수 없어요.");
            history = List.copyOf(history);
        }
    }

    @JsonProperty("phase")
    public String phase() {
        return "PLAN";
    }

    @Override
    public String toString() {
        return "SolutionAssistPlanRequest[hasQuestion=" + (question != null) + ", questionIntent=" + questionIntent
                + ", askedCount=" + askedFactKeys.size() + ", remainingClarifications=" + remainingClarifications
                + ", fraudType=" + fraudType + ", historyCount=" + (history == null ? 0 : history.size())
                + ", requestId=" + requestId + "]";
    }
}
