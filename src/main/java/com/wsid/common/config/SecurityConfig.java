package com.wsid.common.config;

import com.wsid.common.error.ErrorCode;
import com.wsid.common.error.ErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${wsid.cors.allowed-origins:}") String configuredOrigins) {
        var source = new UrlBasedCorsConfigurationSource();
        var origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        if (!origins.isEmpty()) {
            var cors = new CorsConfiguration();
            cors.setAllowedOrigins(origins);
            cors.setAllowCredentials(true);
            cors.setAllowedMethods(List.of("GET", "POST", "PATCH"));
            cors.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, "X-CSRF-TOKEN"));
            cors.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
            cors.validateAllowCredentials();
            source.registerCorsConfiguration("/api/**", cors);
        }
        return source;
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            HttpSessionSecurityContextRepository securityContextRepository,
            UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
        var logout = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout");
        var training = PathPatternRequestMatcher.withDefaults().matcher("/api/training/**");
        var trainings = PathPatternRequestMatcher.withDefaults().matcher("/api/trainings/**");
        var solutions = PathPatternRequestMatcher.withDefaults().matcher("/api/solutions/**");
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            // 비로그인 요청의 401은 인증 API와 훈련 서비스가 반환해요.
            // 상담(/api/solutions/**) 변경 요청은 비회원도 쿠키 세션을 쓰므로 로그인 여부와 무관하게 검사해요.
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> {
                if (solutions.matches(request) && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) return true;
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof Long
                        && (logout.matches(request)
                            || ((training.matches(request) || trainings.matches(request))
                                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)));
            }))
            .exceptionHandling(errors -> errors.accessDeniedHandler(csrfAwareAccessDeniedHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/guides/fraud-types").permitAll()
                // SOL-02~05는 각 API 구현 시 소유권 검사와 함께 공개해요.
                .requestMatchers(HttpMethod.POST, "/api/solutions/sessions").permitAll()
                .requestMatchers("/api/auth/**", "/api/users/me", "/api/training/**", "/api/trainings/**",
                        "/api/content/**").permitAll()
                .anyRequest().authenticated());
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
