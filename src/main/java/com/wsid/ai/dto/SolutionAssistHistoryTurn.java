package com.wsid.ai.dto;

/** 상담 assist history의 한 턴(계약 v1.1 제안). 내용은 AI 요청에만 쓰고 toString·로그에 남기지 않아요. */
public record SolutionAssistHistoryTurn(String role, String content) {
    public static final int MAX_CONTENT_LENGTH = 2_000;

    public SolutionAssistHistoryTurn {
        if (!("USER".equals(role) || "AI".equals(role)) || content == null
                || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("대화 이력 형식이 올바르지 않아요.");
        }
    }

    @Override public String toString() { return "SolutionAssistHistoryTurn[role=" + role + "]"; }
}
