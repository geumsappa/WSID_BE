package com.wsid.common.error;

/** 서비스 계층에서 던지면 GlobalExceptionHandler가 오류 응답으로 바꿔요. */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
