package com.wsid.report.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 저장할 평가 데이터. 원문 대화는 포함하지 않아요. */
public record ReportEvaluation(String overallFeedback, List<String> strengths, List<String> improvements,
        List<JsonNode> turnFeedback, MockPageEvaluation mockPageEvaluation, List<String> recommendations) {

    public record MockPageEvaluation(boolean openedPage, boolean attemptedMockLogin, boolean checkedOfficialChannel) {}
}
