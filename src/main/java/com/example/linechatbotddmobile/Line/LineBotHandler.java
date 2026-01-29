package com.example.linechatbotddmobile.Line;

import com.example.linechatbotddmobile.chatgpt.OpenAiService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;

    // เก็บสถานะว่าลูกค้าคนไหนคุยกับคนอยู่ (True = คน, False = AI)
    private final Map<String, Boolean> humanModeMap = new ConcurrentHashMap<>();

    // 🧠 MEMORY: เก็บประวัติการคุย แยกตาม User ID
    private final Map<String, List<Map<String, String>>> userHistoryMap = new ConcurrentHashMap<>();

    // รับค่า ID ของแอดมินจาก application.yml
    @Value("${line.admin.user-id:}")
    private String adminUserId;

    // 🟢 ใส่ ID ร้านของคุณตรงนี้ (อย่าลืม @ ข้างหน้า)
    private static final String SHOP_BASIC_ID = "@837dszgr";

    @Autowired
    public LineBotHandler(OpenAiService openAiService, MessagingApiClient messagingApiClient) {
        this.openAiService = openAiService;
        this.messagingApiClient = messagingApiClient;
    }

    @EventMapping
    public void handleEvent(MessageEvent event) {
        try {
            String userId = event.source().userId();
            String replyToken = event.replyToken();

            // --------------------------------------------------
            // กรณี 1: ลูกค้าส่งข้อความตัวอักษร (Text)
            // --------------------------------------------------
            if (event.message() instanceof TextMessageContent textContent) {
                String userText = textContent.text().trim();

                // 🛠️ เช็ค User ID ของตัวเอง
                if ("#admin_id".equalsIgnoreCase(userText)) {
                    reply(replyToken, "🔑 User ID ของคุณคือ:\n" + userId);
                    return;
                }

                // 🛠️ สั่งรีเซ็ตบอท + ล้างความจำ
                if ("bot_start".equalsIgnoreCase(userText) || "#reset".equalsIgnoreCase(userText)) {
                    humanModeMap.put(userId, false);
                    userHistoryMap.remove(userId); // ล้างความจำ
                    reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (ล้างความจำเรียบร้อย)");
                    return;
                }

                // 🛑 เช็คโหมดคน
                if (humanModeMap.getOrDefault(userId, false)) {
                    return; // ให้แอดมินตอบเอง
                }

                // --- เริ่มกระบวนการ AI Memory ---

                // 1. ดึงประวัติเก่า (ถ้าไม่มีให้สร้างใหม่)
                List<Map<String, String>> currentHistory = userHistoryMap.getOrDefault(userId, new ArrayList<>());

                // 2. เพิ่มข้อความ "ลูกค้า" เข้าไป
                currentHistory.add(Map.of("role", "user", "content", userText));

                // 3. จำกัดความจำ (Sliding Window) เก็บไว้แค่ 10 ข้อความล่าสุด
                if (currentHistory.size() > 10) {
                    // ลบข้อความเก่าสุดออก (Index 0)
                    currentHistory.subList(0, currentHistory.size() - 10).clear();
                }

                System.out.println(">>> User (" + userId + "): " + userText);

                // 4. ส่งประวัติทั้งหมดไปหา OpenAI
                String aiResponse = openAiService.getChatGptResponse(currentHistory);

                System.out.println(">>> AI: " + aiResponse);

                // 🚨 เช็คว่าต้องเรียกแอดมินไหม
                if (aiResponse.contains("[CALL_ADMIN]")) {
                    activateHumanMode(userId, "ลูกค้าสอบถามเรื่องซับซ้อน: " + userText);
                    reply(replyToken, "สักครู่นะครับ แอดมินขอตรวจสอบข้อมูลสักครู่ครับผม (เดี๋ยวแอดมินตัวจริงมาตอบครับ 🏃‍♂️)");
                    return;
                }

                // 5. เพิ่มข้อความ "AI" เข้าไปในประวัติ
                currentHistory.add(Map.of("role", "assistant", "content", aiResponse));

                // 6. บันทึกประวัติกลับลง Map
                userHistoryMap.put(userId, currentHistory);

                // ✅ ตอบกลับลูกค้า
                reply(replyToken, aiResponse);
            }

            // --------------------------------------------------
            // กรณี 2: ลูกค้าส่งรูปภาพ (Image)
            // --------------------------------------------------
            else if (event.message() instanceof ImageMessageContent) {
                activateHumanMode(userId, "📸 ลูกค้าส่งรูปภาพเข้ามาครับ");
                reply(replyToken, "ได้รับรูปภาพแล้วครับผม! 👍 รบกวนรอแอดมินตรวจสอบสภาพเครื่องสักครู่นะครับ");
            }

        } catch (Exception e) {
            log.error("Error in LineBotHandler", e);
        }
    }

    // --- Helper Functions ---
    private void reply(String replyToken, String message) {
        try {
            messagingApiClient.replyMessage(
                    new ReplyMessageRequest(replyToken, List.of(new TextMessage(message)), false)
            ).get();
        } catch (Exception e) {
            log.error("Reply failed", e);
        }
    }

    private void activateHumanMode(String customerId, String reason) {
        humanModeMap.put(customerId, true);

        // แจ้งเตือนแอดมิน (ถ้าตั้งค่า adminUserId ไว้)
        if (adminUserId != null && !adminUserId.isEmpty()) {
            try {
                String chatUrl = "https://manager.line.biz/account/" + SHOP_BASIC_ID + "/chat/" + customerId;
                String alertMsg = "🚨 บอทเรียกแอดมินด่วน!\nCause: " + reason + "\n👉 " + chatUrl;

                messagingApiClient.pushMessage(
                        UUID.randomUUID(),
                        new PushMessageRequest(adminUserId, List.of(new TextMessage(alertMsg)), false, null)
                ).get();
            } catch (Exception e) {
                log.error("Push notification failed", e);
            }
        }
    }
}