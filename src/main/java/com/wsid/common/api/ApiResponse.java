package com.wsid.common.api;

/** 성공 응답 봉투. 기존 API에는 자동 적용하지 않고 필요한 컨트롤러에서 명시적으로 사용해요. */
public record ApiResponse<T>(boolean success, T data) {
    public ApiResponse {
        if (!success || data == null) {
            throw new IllegalArgumentException("성공 응답에는 결과가 필요해요.");
        }
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }
}
