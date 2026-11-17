package com.wsid.solution.dto;

import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.SolutionQuestion;
import com.wsid.solution.domain.SolutionStatus;
import java.util.List;
import java.util.Map;

/** SOL-01 응답. 소유자 ID·원문·AI 원시 응답·카드 관리 정보(version 등)는 넣지 않아요. */
public record StartSolutionResponse(long solutionId, SolutionStatus status, int stateVersion,
        Map<String, String> facts, List<Card> responseCards, Question nextQuestion) {

    public record Card(String code, String priority, String title, List<String> institutionCodes) {
        public static Card from(ResponseCard card) {
            return new Card(card.code(), card.priority().name(), card.title(), card.institutionCodes());
        }
    }

    public record Question(String factKey, String content, List<String> options) {
        public static Question from(SolutionQuestion question) {
            return question == null ? null : new Question(question.factKey(), question.content(), question.options());
        }
    }
}
