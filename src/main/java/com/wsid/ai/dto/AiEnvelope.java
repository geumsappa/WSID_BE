package com.wsid.ai.dto;

/**
 * AI 서비스 응답의 공통 껍데기: {@code {"success":true,"data":{...}}}.
 * 실패면 {@code {"success":false,"error":{"code":"OCR_FAILED","message":"..."}}}로 받는다고 가정해요.
 * 실패 형식은 AI repo와 확정해야 해요.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record AiEnvelope<T>(boolean success, T data, AiError error) {

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record AiError(String code, String message) {
    }

    public String errorCode() {
        return error == null ? null : error.code();
    }
}
