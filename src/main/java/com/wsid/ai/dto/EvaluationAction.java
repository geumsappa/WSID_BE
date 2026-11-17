package com.wsid.ai.dto;

import java.util.regex.Pattern;

/** AI-05의 평가 근거 코드. 실제 수행 여부·출처 확인은 호출 서비스의 책임이에요. */
public record EvaluationAction(String actionCode) {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,100}");

    public EvaluationAction {
        if (actionCode == null || !CODE.matcher(actionCode).matches()) {
            throw new IllegalArgumentException("평가 행동 코드 형식이 올바르지 않아요.");
        }
    }

    @Override
    public String toString() {
        return "EvaluationAction[codeLength=" + actionCode.length() + "]";
    }
}
