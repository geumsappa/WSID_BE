package com.wsid.ai.dto;

import java.util.List;

/**
 * AI가 제안한 추가 질문. FACT는 네 사실 키 하나, CONDITION(v1.2)은 근거 문단 인용 조건 하나를
 * YES/NO/UNKNOWN으로 물어요. content는 화면에만 보여 주고 저장하지 않아요.
 */
public record SolutionAssistClarification(Kind kind, String factKey, SolutionAssistCondition condition,
        String content, List<String> options) {
    public enum Kind { FACT, CONDITION }
    public static final int MAX_CONTENT_LENGTH = 200;
    public static final List<String> OPTIONS = List.of("YES", "NO", "UNKNOWN");

    public SolutionAssistClarification(String factKey, String content, List<String> options) {
        this(Kind.FACT, factKey, null, content, options);
    }

    public SolutionAssistClarification {
        boolean target = kind == Kind.FACT
                ? factKey != null && SolutionExtractRequest.FACT_KEYS.contains(factKey) && condition == null
                : kind == Kind.CONDITION && factKey == null && condition != null;
        if (!target || content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH
                || !OPTIONS.equals(options)) {
            throw new IllegalArgumentException("추가 질문 형식이 올바르지 않아요.");
        }
        options = OPTIONS;
    }

    public boolean isCondition() {
        return kind == Kind.CONDITION;
    }

    @Override
    public String toString() {
        return "SolutionAssistClarification[kind=" + kind + ", factKey=" + factKey + ", condition=" + condition
                + ", contentLength=" + content.length() + "]";
    }
}
