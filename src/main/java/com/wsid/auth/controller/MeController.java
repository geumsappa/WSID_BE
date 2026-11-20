package com.wsid.auth.controller;

import com.wsid.auth.LoginCurrentUser;
import com.wsid.auth.domain.User;
import com.wsid.auth.domain.UserStatus;
import com.wsid.auth.dto.AuthResponses.Success;
import com.wsid.auth.service.LoginService;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
        return Success.of(new Profile(user.getId(), user.getEmail(), user.getGender(), user.getStatus(),
                user.getCreatedAt()));
    }

    public record Profile(long id, String email, String gender, UserStatus status, Instant createdAt) {}
}
