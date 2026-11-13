package com.wsid.common.error;

import org.springframework.http.HttpStatus;

/** 프론트가 분기에 쓰는 오류 코드. 새 오류는 여기에 추가해요. */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요."),
    DISCOVERY_COMPLETED(HttpStatus.CONFLICT, "상황 파악이 이미 끝났어요."),
    WRONG_PHASE(HttpStatus.CONFLICT, "현재 단계에서 처리할 수 없는 요청이에요."),
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    /** 없는 세션과 다른 사람의 세션을 구분하지 않고 같은 404로 응답해요. */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없어요."),
    SESSION_VERSION_CONFLICT(HttpStatus.CONFLICT, "화면이 최신 상태가 아니에요. 다시 불러와 주세요."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 반영된 메시지예요. 화면을 다시 불러와 주세요."),
    SESSION_FINISHED(HttpStatus.CONFLICT, "이미 끝난 훈련이에요."),
    SESSION_EXPIRED(HttpStatus.GONE, "오래 활동이 없어 훈련이 만료됐어요. 새 훈련을 시작해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
