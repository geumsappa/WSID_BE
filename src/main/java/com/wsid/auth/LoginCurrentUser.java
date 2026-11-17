package com.wsid.auth;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.auth.service.LoginService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 로그인 세션에서 현재 활성 회원의 ID를 확인해요. */
@Component
public class LoginCurrentUser implements CurrentUser {
    private final LoginService loginService;

    public LoginCurrentUser(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public long id() {
        return loginService.requireActiveUser(requireUserId(SecurityContextHolder.getContext().getAuthentication()))
                .getId();
    }

    public static long requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new ApiException(ErrorCode.LOGIN_REQUIRED);
        }
        return userId;
    }
}
