package com.example.linechatbotddmobile.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BotUser {

    @Id
    private String userId;

    @Column(name = "is_human_mode")
    private boolean humanMode;


    @Column(columnDefinition = "TEXT")
    private String chatHistoryJson;

    @Column(name = "last_active_time")
    private LocalDateTime lastActiveTime;
}