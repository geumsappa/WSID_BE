package com.wsid.ai.dto;

/** AI-04에 보낼 이전 역할극 메시지. 시스템 안내와 현재 입력은 호출자가 제외해요. */
public record SimulationMessage(String role, String content) {

    public static final int MAX_CONTENT_LENGTH = 1_000;

    public SimulationMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("역할극 메시지 역할이 올바르지 않아요.");
        }
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("역할극 메시지 내용이 올바르지 않아요.");
        }
    }

    @Override
    public String toString() {
        return "SimulationMessage[role=" + role + ", contentLength=" + content.length() + "]";
    }
}
