package com.example.linechatbotddmobile.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_states")
@Data // สร้าง Getter/Setter ให้อัตโนมัติด้วย Lombok
@NoArgsConstructor // สร้าง Default Constructor
@AllArgsConstructor // สร้าง Constructor แบบรับค่าทุกฟิลด์
@Builder // ช่วยให้สร้าง Object ง่ายขึ้น
public class UserState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lineUserId;
    private String serviceName; // เก็บชื่อบริการ เช่น "รีบอลลูน"
    private String currentState; // เก็บ State ปัจจุบัน เช่น "STEP_1_INFO"
    private String previousState;
    @Column(name = "capacity")
    private String capacity;

    @Column(columnDefinition = "TEXT")
    private String lastUserMessage; // 🧠 ความจำ: ข้อความก่อนหน้า

    private String deviceModel;
    private String fullName;

    @Column(name = "province")
    private String province;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0; // กำหนดค่าเริ่มต้นเป็น 0

    @Column(name = "follow_up_reminder_started_at")
    private LocalDateTime followUpReminderStartedAt;

    @Column(name = "follow_up_reminder_sent")
    @Builder.Default
    private Boolean followUpReminderSent = false;
}
