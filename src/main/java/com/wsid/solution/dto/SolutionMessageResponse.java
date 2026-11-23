package com.wsid.solution.dto;

import java.util.List;
import java.util.Map;

/**
 * SOL-03 메시지·SOL-04 사실 수정 공통 상태 갱신 결과. 원문·소유자·내부 카드 버전은 노출하지 않아요.
 * reply: AI-08 도우미 말. 상황 설명(FACT_UPDATE 글)·사실 수정 뒤에만 채우고, 실패하면 null이에요.
 */
public record SolutionMessageResponse(Map<String, String> facts, List<StartSolutionResponse.Card> responseCards,
        StartSolutionResponse.Question nextQuestion, int stateVersion, SolutionAssistant assistant,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String reply) {
    public SolutionMessageResponse(Map<String, String> facts, List<StartSolutionResponse.Card> cards,
            StartSolutionResponse.Question question, int version) { this(facts, cards, question, version, null, null); }
    public SolutionMessageResponse(Map<String, String> facts, List<StartSolutionResponse.Card> cards,
            StartSolutionResponse.Question question, int version, SolutionAssistant assistant) {
        this(facts, cards, question, version, assistant, null);
    }
    public SolutionMessageResponse withReply(String text) {
        return new SolutionMessageResponse(facts, responseCards, nextQuestion, stateVersion, assistant, text);
    }
    public SolutionMessageResponse {
        facts = Map.copyOf(facts);
        responseCards = List.copyOf(responseCards);
    }
}
