package com.wsid.ai.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * v1.2 조건 질문의 대상. 네 사실 밖에서 공식 근거 문단이 안내를 가르는 조건이에요.
 * AI가 만든 질문 문장은 담지 않고, 문단 코드와 그 문단 원문 인용만 담아요.
 * conditionId는 {@code referenceCode#sha256(referenceCode + "\n" + quote) 앞 12자}라 내용이 바뀌면 거부돼요.
 */
public record SolutionAssistCondition(String conditionId, String referenceCode, String quote) {
    public static final int MIN_QUOTE_LENGTH = 8;
    public static final int MAX_QUOTE_LENGTH = 300;

    public SolutionAssistCondition {
        if (!VerifiedReference.isValidCode(referenceCode) || quote == null || quote.isBlank()
                || quote.length() < MIN_QUOTE_LENGTH || quote.length() > MAX_QUOTE_LENGTH
                || conditionId == null || !conditionId.equals(idOf(referenceCode, quote))) {
            throw new IllegalArgumentException("조건 질문 형식이 올바르지 않아요.");
        }
    }

    public static SolutionAssistCondition of(String referenceCode, String quote) {
        return new SolutionAssistCondition(idOf(referenceCode, quote), referenceCode, quote);
    }

    static String idOf(String referenceCode, String quote) {
        if (referenceCode == null || quote == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((referenceCode + "\n" + quote).getBytes(StandardCharsets.UTF_8));
            return referenceCode + "#" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "SolutionAssistCondition[conditionId=" + conditionId + ", quoteLength=" + quote.length() + "]";
    }
}
