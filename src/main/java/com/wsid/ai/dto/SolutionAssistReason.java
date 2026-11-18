package com.wsid.ai.dto;

/** UNABLE_TO_ANSWER 이유. LIMIT_REACHED는 추가 질문·재검색 상한에 도달한 PLAN에서만 써요. */
public enum SolutionAssistReason {
    OUT_OF_SCOPE,
    NO_EVIDENCE,
    LIMIT_REACHED
}
