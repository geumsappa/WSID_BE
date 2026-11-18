package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 상담 assist ANSWER 요청(계약 초안 v1). Spring이 승인 원본과 대조한 근거만 담아요. 새 검색은 하지 않아요. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"phase", "question", "questionIntent", "facts", "verifiedReferences"})
public record SolutionAssistAnswerRequest(
        String question, SolutionAssistIntent questionIntent, Map<String, String> facts,
        List<VerifiedReference> verifiedReferences, @JsonIgnore UUID requestId) {

    public SolutionAssistAnswerRequest {
        SolutionAssistFacts.requireQuestion(question);
        if (questionIntent == null || verifiedReferences == null || verifiedReferences.isEmpty()
                || verifiedReferences.size() > SolutionExplainRequest.MAX_REFERENCES || requestId == null) {
            throw new IllegalArgumentException("상담 assist ANSWER 요청 형식이 올바르지 않아요.");
        }
        facts = SolutionAssistFacts.requireAll(facts);
        var codes = new HashSet<String>();
        for (VerifiedReference reference : verifiedReferences) {
            if (reference == null || !codes.add(reference.code())) {
                throw new IllegalArgumentException("답변 근거가 없거나 코드가 중복돼요.");
            }
        }
        verifiedReferences = List.copyOf(verifiedReferences);
    }

    @JsonProperty("phase")
    public String phase() {
        return "ANSWER";
    }

    @Override
    public String toString() {
        return "SolutionAssistAnswerRequest[hasQuestion=" + (question != null) + ", questionIntent=" + questionIntent
                + ", referenceCount=" + verifiedReferences.size() + ", requestId=" + requestId + "]";
    }
}
