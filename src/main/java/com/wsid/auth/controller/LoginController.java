package com.wsid.auth.controller;

import com.wsid.auth.dto.AuthRequests.Login;
import com.wsid.auth.domain.User;
import com.wsid.auth.dto.AuthResponses.Success;
import com.wsid.auth.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import static com.wsid.auth.LoginCurrentUser.requireUserId;

@RestController
@RequestMapping("/api/auth")
public class LoginController {
    private final LoginService loginService;
    private final HttpSessionSecurityContextRepository contexts;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public LoginController(LoginService loginService, HttpSessionSecurityContextRepository contexts) {
        this.loginService = loginService;
        this.contexts = contexts;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Success<LoginResult> login(@Valid @RequestBody Login login,
            HttpServletRequest request, HttpServletResponse response) {
        User user = loginService.authenticate(login);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.getId(), null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return Success.of(new LoginResult(UserSummary.from(user)));
    }

    @GetMapping("/session")
    public Success<Session> session(Authentication authentication) {
        User user = loginService.requireActiveUser(requireUserId(authentication));
        return Success.of(new Session(true, UserSummary.from(user)));
    }

    /**
     * 회원·비회원 모두 발급해요. 비회원 상담(/api/solutions/**)도 쿠키 세션 변경 요청이라 CSRF 토큰이 필요해요.
     * 토큰은 서버 세션에 저장되고 별도 만료·재발급(refresh) 없이 세션과 함께 사라져요.
     */
    @GetMapping("/csrf")
    public Success<Csrf> csrf(@RequestAttribute(name = "_csrf") CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Success.of(new Csrf(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        requireUserId(authentication);
        logoutHandler.logout(request, response, authentication);
    }

    public record LoginResult(UserSummary user) {}
    public record Session(boolean authenticated, UserSummary user) {}
    public record Csrf(String headerName, String token) {}

    public record UserSummary(long id, String email, String gender) {
        static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getEmail(), user.getGender());
        }
    }
}
