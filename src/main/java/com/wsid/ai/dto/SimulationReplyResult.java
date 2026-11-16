package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.regex.Pattern;

/** AI-04 응답 data. 종료 여부·행동의 의미는 호출하는 훈련 계층이 판단해요. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationReplyResult(
        String reply, List<String> recognizedActions, boolean shouldFinish, Void mockPage) {

    public static final int MAX_REPLY_LENGTH = 1_000;
    public static final int MAX_ACTIONS = 10;
    private static final Pattern ACTION_CODE = Pattern.compile("[A-Z0-9_]{1,100}");

    public SimulationReplyResult {
        if (reply == null || reply.isBlank() || reply.length() > MAX_REPLY_LENGTH) {
            throw new IllegalArgumentException("역할극 대사 형식이 올바르지 않아요.");
        }
        if (recognizedActions == null || recognizedActions.size() > MAX_ACTIONS
                || recognizedActions.stream().anyMatch(code -> code == null || !ACTION_CODE.matcher(code).matches())) {
            throw new IllegalArgumentException("역할극 행동 코드 형식이 올바르지 않아요.");
        }
        // 모르는 코드와 중복도 원래 순서대로 유지해요. 매핑·중복 제거는 훈련 연결의 책임이에요.
        recognizedActions = List.copyOf(recognizedActions);
        // mockPage는 1차에서 null 전용이에요. 객체 지원 시 별도 타입과 계약 검증이 필요해요.
    }

    @Override
    public String toString() {
        return "SimulationReplyResult[replyLength=" + reply.length()
                + ", actionCount=" + recognizedActions.size() + ", shouldFinish=" + shouldFinish + "]";
    }
}
