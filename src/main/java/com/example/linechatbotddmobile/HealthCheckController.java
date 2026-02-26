package com.example.linechatbotddmobile;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthCheckController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> response = new LinkedHashMap<>();

        // ส่งสถานะกลับไปว่า Server ยังทำงานอยู่
        response.put("status", "UP");
        response.put("service", "DD Mobile Line Bot API");
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }

    // เผื่อไว้ทดสอบหน้าเว็บเปล่าๆ (Root URL)
    @GetMapping("/")
    public String home() {
        return "DD Mobile Line Bot is running! 🚀";
    }
}
