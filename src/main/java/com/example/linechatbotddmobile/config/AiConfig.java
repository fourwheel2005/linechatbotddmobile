package com.example.linechatbotddmobile.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                // .defaultSystem("...") // ถ้าอยากตั้งค่า System Prompt แบบ Global ก็ใส่ตรงนี้ได้
                .build();
    }
}