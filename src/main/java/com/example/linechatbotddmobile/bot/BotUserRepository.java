package com.example.linechatbotddmobile.bot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BotUserRepository extends JpaRepository<BotUser, String> {
    // ใน BotUserRepository.java
    List<BotUser> findByHumanModeTrueAndLastActiveTimeBefore(LocalDateTime time);
}