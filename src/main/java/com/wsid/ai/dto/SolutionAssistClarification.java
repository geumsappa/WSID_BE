package com.wsid.ai.dto;

import java.util.List;

/** AI가 제안한 추가 질문. 1차는 네 사실 키 중 하나를 YES/NO/UNKNOWN으로만 물어요. */
public record SolutionAssistClarification(String factKey, String content, List<String> options) {
    public static final int MAX_CONTENT_LENGTH = 200;
    public static final List<String> OPTIONS = List.of("YES", "NO", "UNKNOWN");

    public SolutionAssistClarification {
        if (factKey == null || !SolutionExtractRequest.FACT_KEYS.contains(factKey) || content == null
                || content.isBlank() || content.length() > MAX_CONTENT_LENGTH || !OPTIONS.equals(options)) {
            throw new IllegalArgumentException("추가 질문 형식이 올바르지 않아요.");
        }
        options = OPTIONS;
    }

    @Override
    public String toString() {
        return "SolutionAssistClarification[factKey=" + factKey + ", contentLength=" + content.length() + "]";
    }
}
