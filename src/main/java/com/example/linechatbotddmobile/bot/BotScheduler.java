package com.example.linechatbotddmobile.bot;



import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class BotScheduler {

    @Autowired
    private BotUserRepository botUserRepository;

    // เช็คทุกๆ 1 นาที (60000 ms)
    @Scheduled(fixedRate = 60000)
    public void autoResetHumanMode() {
        // ตั้งเวลาหมดอายุ เช่น 30 นาที
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(30);

        // หา User ที่เปิดโหมดคนค้างไว้ และเงียบไปนานเกิน 30 นาที
        List<BotUser> inactiveUsers = botUserRepository.findByHumanModeTrueAndLastActiveTimeBefore(timeoutThreshold);

        for (BotUser user : inactiveUsers) {
            log.info("Auto-resetting user: {}", user.getUserId());
            user.setHumanMode(false); // ปิดโหมดคน
            user.setChatHistoryJson("[]"); // (ออปชั่นเสริม) ล้างประวัติด้วยเลยถ้าต้องการ
            botUserRepository.save(user);
        }
    }
}
