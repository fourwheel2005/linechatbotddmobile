package com.example.linechatbotddmobile.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "bot_users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BotUser {

    @Id
    private String userId; // ใช้ UserID ของไลน์เป็น Primary Key

    @Column(name = "is_human_mode")
    private boolean humanMode; // true = คุยกับคน, false = คุยกับ AI


    @Column(columnDefinition = "TEXT")
    private String chatHistoryJson;
}