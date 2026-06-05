package com.example.linechatbotddmobile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for a headless LINE webhook service.
 *
 *   - No UI, no admin endpoints, no login flow.
 *   - LINE webhooks POST raw JSON to /callback; CSRF must be disabled or
 *     Spring would reject every request.
 *   - Everything else (/health, /, /actuator/health) is public by design.
 *
 * Signature verification of incoming webhooks is handled by the LINE Bot
 * SDK itself (`@LineMessageHandler` + channel-secret), not by Spring Security.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
