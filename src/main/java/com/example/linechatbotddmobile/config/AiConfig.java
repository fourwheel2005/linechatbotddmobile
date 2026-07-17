package com.example.linechatbotddmobile.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class AiConfig {

    // ⏱️ Timeout ของการเรียก OpenAI — ค่าเดิม "ไม่มี" ทำให้ถ้า OpenAI ช้า/ค้าง
    //    thread ของ webhook จะค้างตลอดไป ไม่มีวันคืน → thread หมด → บอทไม่รับงาน ต้อง restart
    //    connect: รอเชื่อมต่อได้นานสุด, read: รอ response ได้นานสุด (gpt-4o-mini ปกติ 1-3 วิ)
    private static final Duration OPENAI_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration OPENAI_READ_TIMEOUT = Duration.ofSeconds(25);

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        // .defaultSystem("...") // ถ้าอยากตั้งค่า System Prompt แบบ Global ก็ใส่ตรงนี้ได้
        return builder.build();
    }

    /**
     * ใส่ connect/read timeout ให้ RestClient ที่ Spring AI ใช้เรียก OpenAI (blocking .call()).
     * RestClientCustomizer จะถูก apply กับ RestClient.Builder ที่ auto-configure ไว้
     * ซึ่ง Spring AI OpenAI นำไปใช้ — LINE SDK สร้าง HTTP client ของตัวเองแยกต่างหาก จึงไม่กระทบ.
     */
    @Bean
    RestClientCustomizer openAiTimeoutRestClientCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(OPENAI_CONNECT_TIMEOUT)
                .withReadTimeout(OPENAI_READ_TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        return restClientBuilder -> restClientBuilder.requestFactory(requestFactory);
    }
}
