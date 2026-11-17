package com.wsid.common.error;

import org.springframework.http.HttpStatus;

/** 프론트가 분기에 쓰는 오류 코드. 새 오류는 여기에 추가해요. */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입한 이메일이에요. 로그인하거나 인증번호를 재발송해 주세요."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "유효한 인증 코드가 없어요. 인증번호를 재발송해 주세요."),
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않아요."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수를 초과했어요. 잠시 후 다시 시도해 주세요."),
    MAIL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "인증 메일을 발송하지 못했어요. 잠시 후 재발송해 주세요."),
    AUTH_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "인증 서비스를 사용할 수 없어요. 잠시 후 다시 시도해 주세요."),
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    GUIDE_NOT_FOUND(HttpStatus.NOT_FOUND, "가이드를 찾을 수 없어요."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없어요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요."),
    /** 없는 세션과 다른 사람의 세션을 구분하지 않고 같은 404로 응답해요. */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없어요."),
    TRAINING_NOT_FOUND(HttpStatus.NOT_FOUND, "훈련을 찾을 수 없어요."),
    INVALID_TRAINING_PHASE(HttpStatus.CONFLICT, "현재 훈련 단계에서 사용할 수 없는 요청이에요."),
    STATE_VERSION_CONFLICT(HttpStatus.CONFLICT, "화면이 최신 상태가 아니에요. 다시 불러와 주세요."),
    SENSITIVE_DATA_DETECTED(HttpStatus.BAD_REQUEST, "개인정보를 빼고 다시 입력해 주세요."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 최대 3장, 장당 5MB까지 첨부할 수 있어요."),
    OCR_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "사진 속 글자를 읽지 못했어요. 내용을 직접 입력해 주세요."),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스를 사용할 수 없어요. 잠시 후 다시 시도해 주세요."),
    AI_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI 요청이 많아요. 잠시 후 다시 시도해 주세요."),
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
