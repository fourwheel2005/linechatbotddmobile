package com.example.linechatbotddmobile.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "user_states")
public class UserState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lineUserId;
    private String serviceName; // เก็บชื่อบริการ เช่น "รีบอลลูน"
    private String currentState; // เก็บ State ปัจจุบัน เช่น "STEP_1_INFO"
    private String previousState;

    @Column(columnDefinition = "TEXT")
    private String lastUserMessage; // 🧠 ความจำ: ข้อความก่อนหน้า

    private String deviceModel;
    private String fullName;
}