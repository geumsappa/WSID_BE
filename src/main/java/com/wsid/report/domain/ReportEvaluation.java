package com.wsid.report.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 저장할 평가 데이터. 원문 대화는 포함하지 않아요. */
public record ReportEvaluation(String overallFeedback, List<String> strengths, List<String> improvements,
        List<JsonNode> turnFeedback, MockPageEvaluation mockPageEvaluation, List<String> recommendations) {

    /** clickedAppLink: 훈련 중 가상 앱 설치 링크([모의앱])를 눌렀는지. 예전 리포트에는 없어서 false로 읽혀요. */
    public record MockPageEvaluation(boolean openedPage, boolean attemptedMockLogin, boolean checkedOfficialChannel,
            boolean clickedAppLink) {
        public MockPageEvaluation(boolean openedPage, boolean attemptedMockLogin, boolean checkedOfficialChannel) {
            this(openedPage, attemptedMockLogin, checkedOfficialChannel, false);
        }
    }
}
