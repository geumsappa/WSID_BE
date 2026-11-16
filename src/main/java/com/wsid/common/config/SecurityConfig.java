package com.wsid.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            HttpSessionSecurityContextRepository securityContextRepository) throws Exception {
        var logout = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout");
        var training = PathPatternRequestMatcher.withDefaults().matcher("/api/training/**");
        var trainings = PathPatternRequestMatcher.withDefaults().matcher("/api/trainings/**");
        http
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            // 비로그인 요청의 401은 인증 API와 훈련 서비스가 반환해요.
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof Long
                        && (logout.matches(request)
                            || ((training.matches(request) || trainings.matches(request))
                                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)));
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/guides/fraud-types").permitAll()
                .requestMatchers("/api/auth/**", "/api/users/me", "/api/training/**", "/api/trainings/**",
                        "/api/solution/**", "/api/content/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
