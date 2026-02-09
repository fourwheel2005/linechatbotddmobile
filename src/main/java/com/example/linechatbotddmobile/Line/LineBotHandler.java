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
import com.linecorp.bot.webhook.model.FollowEvent;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;
    private final BotUserRepository botUserRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 🟢 แก้ไขจุดที่ 1: เปลี่ยนจาก String เป็น List<String> เพื่อรับหลาย ID
    @Value("${line.admin.user-ids:}")
    private List<String> adminUserIds;

    @Autowired
    public LineBotHandler(OpenAiService openAiService,
                          MessagingApiClient messagingApiClient,
                          BotUserRepository botUserRepository) {
        this.openAiService = openAiService;
        this.messagingApiClient = messagingApiClient;
        this.botUserRepository = botUserRepository;
    }

    // =========================================================================
    // 🟢 Event 1: ลูกค้าใหม่กด "เพิ่มเพื่อน" (Add Friend)
    // =========================================================================
    @EventMapping
    public void handleFollow(FollowEvent event) {
        String userId = event.source().userId();

        // ลูกค้าใหม่ 100% -> สร้าง User และ "เปิดบอททันที" (HumanMode = false)
        BotUser newUser = new BotUser(userId, false, "[]", LocalDateTime.now());
        botUserRepository.save(newUser);

        reply(event.replyToken(), "สวัสดีครับ! 👋 น้องดีดี ยินดีให้บริการครับ");

        log.info("New friend added: {}", userId);
    }

    // =========================================================================
    // 🔵 Event 2: มีข้อความเข้า (ทั้งลูกค้าเก่าและใหม่)
    // =========================================================================
    @EventMapping
    public void handleEvent(MessageEvent event) {
        try {
            String userId = event.source().userId();
            String replyToken = event.replyToken();

            // ------------------------------------------------------------------
            // 👑 Admin Remote Control: แอดมินสั่งรีเซ็ตลูกค้าผ่านแชทส่วนตัว
            // ------------------------------------------------------------------
            // 🟢 แก้ไขจุดที่ 2: เช็คว่าคนส่ง เป็นหนึ่งใน Admin หรือไม่
            if (event.message() instanceof TextMessageContent adminText && adminUserIds.contains(userId)) {
                String command = adminText.text().trim();
                // คำสั่ง: reset U1234...
                if (command.startsWith("reset ")) {
                    String targetId = command.split(" ")[1];
                    Optional<BotUser> targetOpt = botUserRepository.findById(targetId);
                    if (targetOpt.isPresent()) {
                        BotUser t = targetOpt.get();
                        t.setHumanMode(false);
                        t.setChatHistoryJson("[]");
                        botUserRepository.save(t);
                        reply(replyToken, "✅ Reset ลูกค้า " + targetId + " เรียบร้อยครับ");
                    } else {
                        reply(replyToken, "❌ ไม่พบ UserID นี้ในระบบ");
                    }
                    return;
                }
            }

            // ------------------------------------------------------------------
            // 🛡️ User Safety Logic: จัดการลูกค้าเก่า vs ใหม่
            // ------------------------------------------------------------------
            BotUser botUser = botUserRepository.findById(userId)
                    .orElseGet(() -> {
                        // 🚨 ถ้าไม่เจอใน DB แสดงว่าเป็น "ลูกค้าเก่า" -> ให้เป็น HumanMode = true (เงียบไว้ก่อน)
                        BotUser oldUser = new BotUser(userId, true, "[]", LocalDateTime.now());
                        botUserRepository.save(oldUser);
                        return oldUser;
                    });

            // อัปเดตเวลาล่าสุดเสมอ
            botUser.setLastActiveTime(LocalDateTime.now());
            botUserRepository.save(botUser);

            List<Map<String, String>> currentHistory = parseHistory(botUser.getChatHistoryJson());

            // --------------------------------------------------
            // กรณี: ข้อความ Text
            // --------------------------------------------------
            if (event.message() instanceof TextMessageContent textContent) {
                String userText = textContent.text().trim();

                // 🛠️ Reset Bot (ลูกค้าพิมพ์เอง)
                if ("bot_start".equalsIgnoreCase(userText) || "#reset".equalsIgnoreCase(userText)) {
                    botUser.setHumanMode(false);
                    botUser.setChatHistoryJson("[]");
                    botUserRepository.save(botUser);
                    reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (Reset Complete)");
                    return;
                }

                // 🛑 เช็คโหมดคน (Human Mode)
                if (botUser.isHumanMode()) {

                    // ⭐ Wake Word System (ระบบปลุกบอทสำหรับลูกค้าเก่า) =====================
                    List<String> wakeWords = Arrays.asList(
                            "สนใจ", "ราคา", "ผ่อน", "โปร",
                            "ดาวน์", "เท่าไหร่", "กี่บาท", "ซื้อ",
                            "iPhone", "iphone", "ไอโฟน", "12", "13", "14", "15", "16",
                            "ตาราง", "เอกสาร", "คนละครึ่ง", "สอบถาม"
                    );

                    boolean isWakeWord = wakeWords.stream().anyMatch(userText::contains);

                    if (isWakeWord) {
                        log.info("Wake word detected! Switching user {} to AI mode.", userId);
                        botUser.setHumanMode(false); // ปลดล็อค
                        botUserRepository.save(botUser);
                    } else {
                        return; // ⛔ ถ้าพิมพ์เรื่องอื่น ให้เงียบ
                    }
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
            // กรณี: รูปภาพ (ลูกค้าส่งรูปมา -> เรียกแอดมินเสมอ)
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
        botUser.setHumanMode(true);
        botUser.setLastActiveTime(LocalDateTime.now());
        botUserRepository.save(botUser);

        // 🟢 ตรวจสอบว่ามี Admin ให้ส่งหาหรือไม่
        if (adminUserIds != null && !adminUserIds.isEmpty()) {
            try {
                // ดึงโปรไฟล์ลูกค้า
                var profile = messagingApiClient.getProfile(botUser.getUserId()).get();
                // เตรียมข้อความแจ้งเตือน
                List<Message> messages = getMessages(botUser, reason, profile);

                // ✅ แก้ไข: ใช้ MulticastRequest (ไม่มีคำว่า Message)
                // พารามิเตอร์: (List<String> to, List<Message> messages, boolean notificationDisabled, List<String> customAggregationUnits)
                MulticastRequest multicastRequest = new MulticastRequest(
                        messages, // รายชื่อ Admin (List<String>)
                        adminUserIds,     // ข้อความที่จะส่ง
                        false,        // แจ้งเตือนปกติ (ไม่ปิด notification)
                        null          // customAggregationUnits (ใส่ null ได้ถ้าไม่ใช้)
                );

                // สั่งยิง Multicast
                messagingApiClient.multicast(
                        UUID.randomUUID(), // X-Line-Retry-Key
                        multicastRequest
                ).get();

                log.info("Notified {} admins about user {}", adminUserIds.size(), botUser.getUserId());

            } catch (Exception e) {
                log.error("Failed to notify admins", e);
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