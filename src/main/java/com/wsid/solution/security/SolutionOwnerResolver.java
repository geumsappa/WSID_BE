package com.wsid.solution.security;

import com.wsid.auth.LoginCurrentUser;
import com.wsid.auth.service.LoginService;
import com.wsid.solution.domain.SolutionOwner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 상담 소유자를 서버가 결정해요. 클라이언트가 보낸 사용자·소유자 ID는 쓰지 않아요.
 * <ul>
 *   <li>로그인 principal(Long)이 있으면 활성 회원인지 확인하고 회원 소유. 비활성·삭제 회원은 401이며 비회원으로 바꾸지 않아요.</li>
 *   <li>인증 정보가 없거나 익명 토큰이면 HTTP 세션의 익명 UUID 소유.</li>
 * </ul>
 * 로그인 뒤 세션에 익명 UUID가 남아 있어도 회원이 우선이고, 기존 익명 상담을 자동 이관하지 않아요.
 */
@Component
public class SolutionOwnerResolver {
    private final LoginService loginService;
    private final AnonymousOwnerIssuer anonymousOwners;

    public SolutionOwnerResolver(LoginService loginService, AnonymousOwnerIssuer anonymousOwners) {
        this.loginService = loginService;
        this.anonymousOwners = anonymousOwners;
    }

    public SolutionOwner resolve(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return SolutionOwner.anonymous(anonymousOwners.ownerFor(request.getSession(true)));
        }
        long userId = LoginCurrentUser.requireUserId(authentication);
        return SolutionOwner.member(loginService.requireActiveUser(userId).getId());
    }
}
