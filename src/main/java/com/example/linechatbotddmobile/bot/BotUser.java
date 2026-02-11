package com.example.linechatbotddmobile.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_users")
@Data
@NoArgsConstructor
@Getter
@Setter
public class BotUser {

    @Id
    private String userId;

    @Column(name = "is_human_mode")
    private boolean humanMode;


    @Column(columnDefinition = "TEXT")
    private String chatHistoryJson;

    @Column(name = "last_active_time")
    private LocalDateTime lastActiveTime;

    // สถานะ: "NORMAL", "WAIT_IMAGE", "WAIT_CREDIT", "WAIT_DOCS"
    private String currentStatus = "NORMAL";
}