package com.wsid.solution.dto;

import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.SolutionQuestion;
import com.wsid.solution.domain.SolutionStatus;
import java.util.List;
import java.util.Map;

/**
 * SOL-01 응답. 소유자 ID·원문·AI 원시 응답·카드 관리 정보(version 등)는 넣지 않아요.
 * reply: AI-08이 만든 도우미 말 (확인된 사실·검토 카드 기반). 만들지 못하면 null이고 화면이 기본 문장을 써요.
 */
public record StartSolutionResponse(long solutionId, SolutionStatus status, int stateVersion,
        Map<String, String> facts, List<Card> responseCards, Question nextQuestion,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String reply) {

    public StartSolutionResponse(long solutionId, SolutionStatus status, int stateVersion,
            Map<String, String> facts, List<Card> responseCards, Question nextQuestion) {
        this(solutionId, status, stateVersion, facts, responseCards, nextQuestion, null);
    }

    public StartSolutionResponse withReply(String text) {
        return new StartSolutionResponse(solutionId, status, stateVersion, facts, responseCards, nextQuestion, text);
    }

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
