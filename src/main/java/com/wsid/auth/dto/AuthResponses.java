package com.wsid.auth.dto;

import com.wsid.auth.domain.UserStatus;

public final class AuthResponses {
    private AuthResponses() {}
    public record Success<T>(boolean success, T data) {
        public static <T> Success<T> of(T data) { return new Success<>(true, data); }
    }
    public record Signup(long userId, String email, UserStatus status, boolean verificationRequired) {}
    public record Resend(String message) {}
    public record Verify(boolean verified, UserStatus status) {}
}
