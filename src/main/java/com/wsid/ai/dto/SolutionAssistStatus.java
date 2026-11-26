package com.wsid.ai.dto;

/** 상담 질문·답변 흐름 결과. PLAN은 앞의 세 값 중 ANSWERED를 제외하고, ANSWER는 ANSWERED·UNABLE_TO_ANSWER만 써요. */
public enum SolutionAssistStatus {
    NEED_USER_INPUT,
    EVIDENCE_READY,
    ANSWERED,
    UNABLE_TO_ANSWER
}
