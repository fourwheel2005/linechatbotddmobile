package com.example.linechatbotddmobile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Headless LINE chatbot service — no UI, no admin login.
 *
 * UserDetailsServiceAutoConfiguration is excluded because we don't expose any
 * authenticated endpoint; otherwise Spring Boot would generate a random
 * password at startup and log a noisy "Using generated security password"
 * warning that is meaningless for this deployment.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class LinechatbotddmobileApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinechatbotddmobileApplication.class, args);
    }
}
