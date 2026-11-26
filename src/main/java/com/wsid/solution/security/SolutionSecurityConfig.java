package com.wsid.solution.security;

import com.wsid.common.error.ErrorCode;
import com.wsid.common.error.ErrorResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 피해 대응 상담(/api/solutions/**) 전용 보안 설정. AUTH의 common/config/SecurityConfig는 수정하지 않아요.
 * <ul>
 *   <li>이 체인이 먼저 검사되고 /api/solutions/** 요청만 맡아요. 나머지 경로는 기존 SecurityConfig가 처리해요.</li>
 *   <li>로그인 정보(SecurityContext)·CORS 설정 빈은 AUTH와 같은 것을 써요.</li>
 *   <li>CSRF 토큰은 AUTH와 같은 기본 세션 저장소에 있어서, 회원이 /api/auth/csrf로 받은 토큰도 그대로 통과해요.</li>
 *   <li>변경 요청(POST·PATCH 등)은 회원·비회원 모두 CSRF를 검사해요. GET은 검사하지 않아요.</li>
 *   <li>공개 경로는 구현한 API만 하나씩 열고, 나머지는 막아요.</li>
 * </ul>
 */
@Configuration
public class SolutionSecurityConfig {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    SecurityFilterChain solutionSecurityFilterChain(HttpSecurity http,
            HttpSessionSecurityContextRepository securityContextRepository,
            UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .securityMatcher("/api/solutions/**")
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .exceptionHandling(errors -> errors.accessDeniedHandler(csrfAwareAccessDeniedHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/solutions/csrf").permitAll()          // 비회원 CSRF 발급
                .requestMatchers(HttpMethod.POST, "/api/solutions/sessions").permitAll()     // SOL-01
                .requestMatchers(HttpMethod.GET, "/api/solutions/sessions").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/solutions/sessions/{id}").permitAll() // SOL-02
                .requestMatchers(HttpMethod.POST, "/api/solutions/sessions/{id}/messages").permitAll() // SOL-03
                .requestMatchers(HttpMethod.POST, "/api/solutions/sessions/{id}/images").permitAll()   // SOL-06
                .requestMatchers(HttpMethod.PATCH, "/api/solutions/sessions/{id}/facts").permitAll()    // SOL-04
                .requestMatchers(HttpMethod.POST, "/api/solutions/sessions/{id}/complete").permitAll() // SOL-05
                .requestMatchers(HttpMethod.DELETE, "/api/solutions/sessions/{id}").permitAll() // 상담 삭제 (소유권은 서비스에서 확인)
                .anyRequest().denyAll());
        return http.build();
    }

    /** CSRF 거부는 공통 오류 JSON(403 CSRF_TOKEN_INVALID)으로, 그 밖의 거부는 기본 처리로 응답해요. */
    private static AccessDeniedHandler csrfAwareAccessDeniedHandler() {
        var fallback = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (!(exception instanceof CsrfException) || response.isCommitted()) {
                fallback.handle(request, response, exception);
                return;
            }
            response.setStatus(ErrorCode.CSRF_TOKEN_INVALID.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(JSON.writeValueAsString(ErrorResponse.of(ErrorCode.CSRF_TOKEN_INVALID)));
        };
    }
}
