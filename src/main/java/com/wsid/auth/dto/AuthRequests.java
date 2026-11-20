package com.wsid.auth.dto;

import com.wsid.auth.domain.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public final class AuthRequests {
    private AuthRequests() {}

    public static String normalizeEmail(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public record Signup(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "(?s)(?=.*[A-Za-z])(?=.*[0-9]).*") String password,
            Gender gender) {
        public Signup { email = normalizeEmail(email); }
        @Override public String toString() { return "Signup[redacted]"; }
    }

    public record Login(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 72) String password) {
        public Login { email = normalizeEmail(email); }
        @Override public String toString() { return "Login[redacted]"; }
    }

    public record Resend(@NotBlank @Email @Size(max = 320) String email) {
        public Resend { email = normalizeEmail(email); }
        @Override public String toString() { return "Resend[redacted]"; }
    }

    public record Verify(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
        public Verify { email = normalizeEmail(email); }
        @Override public String toString() { return "Verify[redacted]"; }
    }
}
