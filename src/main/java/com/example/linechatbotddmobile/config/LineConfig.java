package com.example.linechatbotddmobile.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LineConfig {

    /**
     * 🛡️ สร้าง ObjectMapper ส่วนกลางที่ปลอดภัย (Safe Parser)
     * ใช้สำหรับแปลง JSON จาก LINE ให้เป็น Object หรือสร้าง Flex Message
     */
    @Bean
    public ObjectMapper customObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 🌟 ป้องกันระบบล่ม: หาก LINE ส่งฟิลด์ใหม่ใน JSON ที่คลาส Java ของเราไม่มี ให้ข้ามไปได้เลย
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}