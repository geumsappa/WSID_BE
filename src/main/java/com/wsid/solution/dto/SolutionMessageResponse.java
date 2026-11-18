package com.wsid.solution.dto;

import java.util.List;
import java.util.Map;

/** SOL-03 메시지·SOL-04 사실 수정 공통 상태 갱신 결과. 원문·소유자·내부 카드 버전은 노출하지 않아요. */
public record SolutionMessageResponse(Map<String, String> facts, List<StartSolutionResponse.Card> responseCards,
        StartSolutionResponse.Question nextQuestion, int stateVersion, SolutionAssistant assistant) {
    public SolutionMessageResponse(Map<String, String> facts, List<StartSolutionResponse.Card> cards,
            StartSolutionResponse.Question question, int version) { this(facts, cards, question, version, null); }
    public SolutionMessageResponse {
        facts = Map.copyOf(facts);
        responseCards = List.copyOf(responseCards);
    }
}
