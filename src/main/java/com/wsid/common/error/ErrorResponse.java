package com.wsid.common.error;

/** 모든 오류 응답의 형식. */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message());
    }
}
