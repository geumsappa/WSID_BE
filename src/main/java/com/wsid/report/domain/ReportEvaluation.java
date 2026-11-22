package com.wsid.report.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 저장할 평가 데이터. 원문 대화는 포함하지 않아요. */
public record ReportEvaluation(String overallFeedback, List<String> strengths, List<String> improvements,
        List<JsonNode> turnFeedback, MockPageEvaluation mockPageEvaluation, List<String> recommendations,
        List<ReportSource> sources) {

    public ReportEvaluation { sources = sources == null ? List.of() : List.copyOf(sources); }

    public ReportEvaluation(String overallFeedback, List<String> strengths, List<String> improvements,
            List<JsonNode> turnFeedback, MockPageEvaluation mockPageEvaluation, List<String> recommendations) {
        this(overallFeedback, strengths, improvements, turnFeedback, mockPageEvaluation, recommendations, List.of());
    }

    public ReportEvaluation withSources(List<ReportSource> sourceCredits) {
        return new ReportEvaluation(overallFeedback, strengths, improvements, turnFeedback, mockPageEvaluation, recommendations, sourceCredits);
    }

    public record MockPageEvaluation(boolean openedPage, boolean attemptedMockLogin, boolean checkedOfficialChannel) {}
}
