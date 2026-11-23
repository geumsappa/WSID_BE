package com.wsid.auth.controller;

import com.wsid.auth.LoginCurrentUser;
import com.wsid.auth.domain.User;
import com.wsid.auth.domain.UserStatus;
import com.wsid.auth.dto.AuthResponses.Success;
import com.wsid.auth.service.LoginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final LoginService loginService;

    public MeController(LoginService loginService) {
        this.loginService = loginService;
    }

    @GetMapping("/api/users/me")
    public Success<Profile> me(Authentication authentication) {
        User user = loginService.requireActiveUser(LoginCurrentUser.requireUserId(authentication));
        return Success.of(profile(user));
    }

    /** 시뮬레이션 시작 전 AI 처리(해외 전송) 안내 동의를 계정에 기록해요. version은 프론트 안내 문구 버전 */
    @PostMapping("/api/users/me/ai-consent")
    public Success<Profile> agreeAiProcessing(Authentication authentication, @Valid @RequestBody AiConsentRequest request) {
        User user = loginService.agreeAiProcessing(LoginCurrentUser.requireUserId(authentication), request.version());
        return Success.of(profile(user));
    }

    private static Profile profile(User user) {
        return new Profile(user.getId(), user.getEmail(), user.getGender(), user.getStatus(), user.getCreatedAt(),
                user.getAiConsentVersion(), user.getAiConsentAt());
    }

    public record Profile(long id, String email, String gender, UserStatus status, Instant createdAt,
            String aiConsentVersion, Instant aiConsentAt) {}

    public record AiConsentRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,20}") String version) {}
}
