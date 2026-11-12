package com.wsid.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // TODO(AUTH): 세션 로그인을 붙일 때 CSRF 정책을 정해요. 지금은 API 개발을 위해 꺼 둔 상태예요.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/api/training/**", "/api/solution/**", "/api/content/**").permitAll()
                // TODO(AUTH-002): 로그인을 붙이면 training, solution도 로그인 사용자만 쓰도록 좁혀요 (user_id NOT NULL)
                .anyRequest().authenticated());
        return http.build();
    }
}
