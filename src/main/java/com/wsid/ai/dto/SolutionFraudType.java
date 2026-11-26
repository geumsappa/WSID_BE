package com.wsid.ai.dto;

import java.util.Set;

/**
 * AI-06이 제안하는 피해 대응 사기 유형 코드(AI 레포 app/schemas/solution.py FraudType과 같은 값).
 * 상담에 fraud_type_code로 저장하고 assist PLAN·ANSWER 요청에 넘겨 유형에 맞는 근거를 고르게 해요.
 */
public final class SolutionFraudType {
    public static final Set<String> CODES = Set.of("MESSENGER_PHISHING", "SMISHING", "LOAN_FRAUD",
            "INSTITUTION_IMPERSONATION", "INVESTMENT_FRAUD", "SECONDHAND_TRADE");

    private SolutionFraudType() {}

    /** AI 제안값용: 모르는 값이면 사실 추출 전체를 실패시키지 않고 null로 버려요. */
    public static String orNull(String code) {
        return code != null && CODES.contains(code) ? code : null;
    }

    /** 요청 구성용: null 또는 허용 코드만 받아요. */
    public static String require(String code) {
        if (code != null && !CODES.contains(code)) throw new IllegalArgumentException("사기 유형 코드가 올바르지 않아요.");
        return code;
    }
}
