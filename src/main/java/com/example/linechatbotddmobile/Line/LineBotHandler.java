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
import java.util.*;

@Slf4j
@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;
    private final BotUserRepository botUserRepository; // ✅ เรียกใช้ Database
    private final ObjectMapper objectMapper = new ObjectMapper(); // ✅ ตัวช่วยแปลง JSON

    @Value("${line.admin.user-id:}")
    private String adminUserId;

    // ใส่ ID ร้านค้าของคุณที่ถูกต้อง
    private static final String SHOP_CHAT_ID = "U23e97d0170118f9b6c5a951625850a7e";

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

            // 1️⃣ ดึงข้อมูล User จาก Database (ถ้าไม่มีให้สร้างใหม่)
            BotUser botUser = botUserRepository.findById(userId)
                    .orElse(new BotUser(userId, false, "[]"));

            // แปลง History จาก JSON String ใน DB ออกมาเป็น List
            List<Map<String, String>> currentHistory = parseHistory(botUser.getChatHistoryJson());

            // --------------------------------------------------
            // กรณี: ข้อความ Text
            // --------------------------------------------------
            if (event.message() instanceof TextMessageContent textContent) {
                String userText = textContent.text().trim();

                // 🛠️ Reset Bot
                if ("bot_start".equalsIgnoreCase(userText) || "#reset".equalsIgnoreCase(userText)) {
                    botUser.setHumanMode(false);
                    botUser.setChatHistoryJson("[]"); // ล้างประวัติ
                    botUserRepository.save(botUser); // ✅ บันทึกลง DB
                    reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (Reset Complete)");
                    return;
                }

                // 🛑 เช็คโหมดคน (ดึงค่าจาก DB)
                if (botUser.isHumanMode()) {
                    return; // ให้แอดมินตอบเอง
                }

                // --- AI Process ---
                currentHistory.add(Map.of("role", "user", "content", userText));

                // ตัด History ให้เหลือ 10 ข้อความล่าสุด (กัน Token เต็ม)
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

                // บันทึกคำตอบ AI ลง History
                currentHistory.add(Map.of("role", "assistant", "content", aiResponse));

                // ✅ บันทึกข้อมูลล่าสุดลง Database
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

    // --- Helper Functions ---

    private void activateHumanMode(BotUser botUser, String reason) {
        // 1. บันทึกลง Database ว่าลูกค้าคนนี้ขอคุยกับคน
        botUser.setHumanMode(true);
        botUserRepository.save(botUser);

        // 2. เช็คว่ามี Admin ID หรือไม่ ถ้ามีให้ส่งแจ้งเตือน
        if (adminUserId != null && !adminUserId.isEmpty()) {
            try {
                // --- ส่วนที่แก้ Error ---
                // ใช้ 'var' เพื่อให้ Java จับคู่ Type ให้อัตโนมัติ (แก้ปัญหา Type Mismatch)
                var profile = messagingApiClient.getProfile(botUser.getUserId()).get();

                List<Message> messages = getMessages(botUser, reason, profile);

                messagingApiClient.pushMessage(
                        UUID.randomUUID(),
                        new PushMessageRequest(
                                adminUserId,
                                messages,
                                false,
                                null
                        )
                ).get();

            } catch (Exception e) {
                log.error("Failed to notify admin", e);
            }
        }
    }

    private static List<Message> getMessages(BotUser botUser, String reason, Result<UserProfileResponse> profile) {
        String displayName = profile.body().displayName();
        URI pictureUrl = profile.body().pictureUrl();

        // เตรียมข้อความที่จะส่งหาแอดมิน
        List<Message> messages = new ArrayList<>();

        // ข้อความแจ้งเตือน
        String alertMsg = "🚨 ลูกค้าต้องการคุยกับคน!\n" +
                "👤 ชื่อ: " + displayName + "\n" +
                "📝 สาเหตุ: " + reason + "\n" +
                "👇 (ค้นหาชื่อนี้ในแชทได้เลยครับ)";
        messages.add(new TextMessage(alertMsg));

        // ข้อความรูปภาพ (เช็คก่อนว่าลูกค้ามีรูปไหม เพื่อป้องกัน Error)
        if (pictureUrl != null) {
            messages.add(new ImageMessage(pictureUrl, pictureUrl));
        }

        // ข้อความ UserID (เผื่อต้องใช้ค้นหา)
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

    // แปลง JSON String -> List<Map>
    private List<Map<String, String>> parseHistory(String json) {
        try {
            if (json == null || json.isEmpty()) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}