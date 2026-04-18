package com.example.linechatbotddmobile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // ปิด CSRF Protection (จำเป็นสำหรับ Webhook/API)
                .csrf(AbstractHttpConfigurer::disable)
                // อนุญาตให้ทุกคนเข้าถึง /callback ได้โดยไม่ต้อง Login
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/callback").permitAll()
                        .anyRequest().authenticated() // Path อื่นๆ ต้อง Login (หรือปรับตามเหมาะสม)
                );
        return http.build();
    }
}