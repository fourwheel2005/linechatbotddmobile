package com.example.linechatbotddmobile.Line;

import com.example.linechatbotddmobile.bot.BotUser;
import com.example.linechatbotddmobile.bot.BotUserRepository;
import com.example.linechatbotddmobile.chatgpt.OpenAiService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.*;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;

import com.linecorp.bot.webhook.model.TextMessageContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.time.LocalDateTime; // ✅ Import เวลา
import java.util.*;

@Slf4j
@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;
    private final BotUserRepository botUserRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${line.admin.user-id:}")
    private String adminUserId;

    @Autowired
    public LineBotHandler(OpenAiService openAiService,
                          MessagingApiClient messagingApiClient,
                          BotUserRepository botUserRepository) {
        this.openAiService = openAiService;
        this.messagingApiClient = messagingApiClient;
        this.botUserRepository = botUserRepository;
    }

    @EventMapping
    public void handleEvent(MessageEvent event) {
        try {
            String userId = event.source().userId();
            String replyToken = event.replyToken();

            // 1️⃣ ดึงข้อมูล User (ถ้าไม่มีให้สร้างใหม่ พร้อมเวลาเริ่มต้น)
            BotUser botUser = botUserRepository.findById(userId)
                    .orElse(new BotUser(userId, false, "[]", LocalDateTime.now()));

            // ✅ อัปเดตเวลาล่าสุดเสมอ (เพื่อให้ Scheduler รู้ว่ายังคุยอยู่)
            botUser.setLastActiveTime(LocalDateTime.now());
            botUserRepository.save(botUser);

            List<Map<String, String>> currentHistory = parseHistory(botUser.getChatHistoryJson());

            // --------------------------------------------------
            // กรณี: ข้อความ Text
            // --------------------------------------------------
            if (event.message() instanceof TextMessageContent textContent) {
                String userText = textContent.text().trim();

                // 🛠️ Reset Bot (Manual)
                if ("bot_start".equalsIgnoreCase(userText) || "#reset".equalsIgnoreCase(userText)) {
                    botUser.setHumanMode(false);
                    botUser.setChatHistoryJson("[]");
                    botUserRepository.save(botUser);
                    reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (Reset Complete)");
                    return;
                }

                // 🛑 เช็คโหมดคน
                if (botUser.isHumanMode()) {
                    return; // แอดมินคุยอยู่ บอทไม่ยุ่ง
                }

                // --- AI Process ---
                currentHistory.add(Map.of("role", "user", "content", userText));

                if (currentHistory.size() > 10) {
                    currentHistory = currentHistory.subList(currentHistory.size() - 10, currentHistory.size());
                }

                String aiResponse = openAiService.getChatGptResponse(currentHistory);

                // 🚨 เช็ค Trigger เรียกแอดมิน
                if (aiResponse.contains("[CALL_ADMIN]")) {
                    activateHumanMode(botUser, "ลูกค้าสอบถามเรื่องซับซ้อน: " + userText);
                    reply(replyToken, "สักครู่นะครับ แอดมินขอตรวจสอบข้อมูลสักครู่ครับผม (เดี๋ยวแอดมินตัวจริงมาตอบครับ 🏃‍♂️)");
                    return;
                }

                currentHistory.add(Map.of("role", "assistant", "content", aiResponse));
                botUser.setChatHistoryJson(objectMapper.writeValueAsString(currentHistory));
                botUserRepository.save(botUser);

                reply(replyToken, aiResponse);
            }

            // --------------------------------------------------
            // กรณี: รูปภาพ
            // --------------------------------------------------
            else if (event.message() instanceof ImageMessageContent) {
                activateHumanMode(botUser, "📸 ลูกค้าส่งรูปภาพเข้ามาครับ");
                reply(replyToken, "ได้รับรูปภาพแล้วครับผม! 👍 รบกวนรอแอดมินตรวจสอบสภาพเครื่องสักครู่นะครับ");
            }

        } catch (Exception e) {
            log.error("Error in LineBotHandler", e);
        }
    }


    private void activateHumanMode(BotUser botUser, String reason) {
        botUser.setHumanMode(true);
        botUser.setLastActiveTime(LocalDateTime.now()); // ✅ อัปเดตเวลาตอนเริ่มโหมดคนด้วย
        botUserRepository.save(botUser);

        if (adminUserId != null && !adminUserId.isEmpty()) {
            try {
                var profile = messagingApiClient.getProfile(botUser.getUserId()).get();
                List<Message> messages = getMessages(botUser, reason, profile);

                messagingApiClient.pushMessage(
                        UUID.randomUUID(),
                        new PushMessageRequest(adminUserId, messages, false, null)
                ).get();

            } catch (Exception e) {
                log.error("Failed to notify admin", e);
            }
        }
    }

    private static List<Message> getMessages(BotUser botUser, String reason, Result<UserProfileResponse> profile) {
        String displayName = profile.body().displayName();
        URI pictureUrl = profile.body().pictureUrl();

        List<Message> messages = new ArrayList<>();
        String alertMsg = "🚨 ลูกค้าต้องการคุยกับคน!\n" +
                "👤 ชื่อ: " + displayName + "\n" +
                "📝 สาเหตุ: " + reason + "\n" +
                "👇 (ค้นหาชื่อนี้ในแชทได้เลยครับ)";
        messages.add(new TextMessage(alertMsg));

        if (pictureUrl != null) {
            messages.add(new ImageMessage(pictureUrl, pictureUrl));
        }
        messages.add(new TextMessage("🆔 UserID: " + botUser.getUserId()));
        return messages;
    }

    private void reply(String replyToken, String message) {
        try {
            messagingApiClient.replyMessage(
                    new ReplyMessageRequest(replyToken, List.of(new TextMessage(message)), false)
            ).get();
        } catch (Exception e) {
            log.error("Reply failed", e);
        }
    }

    private List<Map<String, String>> parseHistory(String json) {
        try {
            if (json == null || json.isEmpty()) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}