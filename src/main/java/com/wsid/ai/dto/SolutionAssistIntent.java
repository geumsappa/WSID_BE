package com.wsid.ai.dto;

/** 상담 질문 의도. Spring은 질문 원문 대신 이 코드만 저장해요. 목록은 계약 초안 v1이며 팀 합의 대상이에요. */
public enum SolutionAssistIntent {
    TRANSFER_RESPONSE,
    DEVICE_SAFETY,
    INFO_LEAK_RESPONSE,
    CONTACT_RESPONSE,
    GENERAL
}
