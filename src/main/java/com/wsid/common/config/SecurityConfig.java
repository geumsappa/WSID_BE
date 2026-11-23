package com.wsid.common.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

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
        var createTraining = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/trainings");
        var trainingApi = PathPatternRequestMatcher.withDefaults().matcher("/api/trainings/{id}/**");
        var saveReport = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/reports/{id}/save");
        var aiConsent = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/users/me/ai-consent");
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            // 비로그인 요청의 401은 인증 API와 훈련 서비스가 반환해요.
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof Long
                        && (logout.matches(request) || saveReport.matches(request) || createTraining.matches(request)
                            || aiConsent.matches(request)
                            || ((training.matches(request) || trainingApi.matches(request))
                                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)));
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/guides/**", "/api/trainings/active",
                        "/api/trainings/{id}", "/api/trainings/{id}/report").permitAll()
                .requestMatchers(createTraining).permitAll()
                .requestMatchers(trainingApi).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reports", "/api/reports/{id}", "/api/reports/{id}/pdf").permitAll()
                .requestMatchers(saveReport).permitAll()
                .requestMatchers("/api/auth/**", "/api/users/me", "/api/training/**", "/api/solution/**",
                        "/api/content/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
