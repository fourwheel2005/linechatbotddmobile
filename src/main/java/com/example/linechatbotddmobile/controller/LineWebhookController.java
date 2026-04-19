package com.example.linechatbotddmobile.controller; // ปรับให้ตรงกับ package ของคุณ

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.line.ChatFlowManager; // ปรับให้ตรง
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@LineMessageHandler
public class LineWebhookController {

    private final ChatFlowManager chatFlowManager;
    private final MessagingApiClient messagingApiClient;
    private final UserStateRepository userStateRepository;
    private final com.example.linechatbotddmobile.service.line.LineMessageService lineMessageService;

    // ตัวแปรสำหรับหน่วงเวลาการรับรูปภาพ
    private final ConcurrentHashMap<String, Instant> lastImageReceivedTime = new ConcurrentHashMap<>();

    // ID ของกลุ่มแอดมิน (เปลี่ยนเป็นของคุณ)
    private final String MAIN_ADMIN_GROUP_ID = "Ced29a5fec5e581b47ffa61d9845e71bf";

    // ==========================================
    // ✉️ & 📸 รับ Event ข้อความและรูปภาพ
    // ==========================================
    @EventMapping
    public void handleMessageEvent(MessageEvent event) {
        String replyToken = event.replyToken();
        String lineUserId = event.source().userId();

        // 🛑 1. ดักข้อความจาก Group / Room (บอทไม่อ่าน ไม่ตอบในกลุ่ม)
        if (event.source() instanceof com.linecorp.bot.webhook.model.GroupSource groupSource) {
            if (event.message() instanceof TextMessageContent txtMsg && txtMsg.text().trim().equalsIgnoreCase("/groupid")) {
                String groupId = groupSource.groupId();
                log.info("🎯 มีการเรียกดู Group ID: {}", groupId);
                messagingApiClient.replyMessage(new ReplyMessageRequest(
                        replyToken, List.of(new TextMessage("Group ID ของกลุ่มนี้คือ:\n" + groupId)), false
                ));
            } else {
                log.info("🤫 ได้รับข้อความจากกลุ่มแอดมิน บอทจะไม่อ่านและไม่ตอบกลับ");
            }
            return;
        } else if (event.source() instanceof com.linecorp.bot.webhook.model.RoomSource) {
            return;
        }

        // ==========================================
        // 2. กรณีลูกค้าส่ง "ข้อความตัวอักษร"
        // ==========================================
        if (event.message() instanceof TextMessageContent textMessageContent) {
            String userMessage = textMessageContent.text().trim();
            log.info("📩 ได้รับข้อความจากลูกค้า [{}]: {}", lineUserId, userMessage);

            // ดึงหรือสร้าง State ใหม่
            UserState userState = userStateRepository.findByLineUserId(lineUserId)
                    .orElseGet(() -> {
                        UserState newUser = new UserState();
                        newUser.setLineUserId(lineUserId);
                        return newUser;
                    });

            String msg = userMessage.toLowerCase();

            // 🚨 Panic Mode: ตรวจจับคำว่า แอดมิน / คุยกับคน
            boolean isPanic = msg.matches(".*(แอดมิน|ติดต่อแอดมิน|คุยกับคน|อ่านดีๆ|บอท|บอกไปแล้ว|ไม่รู้เรื่อง|อะไรเนี่ย).*");

            if (isPanic) {
                userState.setCurrentState("ADMIN_MODE");
                userState.setLastUserMessage(userMessage); // บันทึกความจำ
                userStateRepository.save(userState);

                String customerName = getCustomerName(lineUserId);

                // แจ้งเตือนแอดมินในกลุ่ม
                lineMessageService.sendEmergencyCard(
                        MAIN_ADMIN_GROUP_ID,
                        "ติดต่อทั่วไป",
                        "general",
                        customerName,
                        lineUserId,
                        "ลูกค้าต้องการคุยกับคน หรือ หงุดหงิดบอท"
                );

                // ตอบกลับลูกค้า
                messagingApiClient.replyMessage(new ReplyMessageRequest(
                        replyToken, List.of(new TextMessage("รับทราบครับ 🙏 แอดมินรับเรื่องแล้ว รบกวนรอสักครู่นะครับ ⏳")), false
                ));
                return; // 🛑 จบการทำงาน ไม่ส่งเข้า Flow
            }

            // 🧠 ส่งเข้า FlowManager เพื่อเลือก Flow บริการ
            try {
                String replyText = chatFlowManager.handleTextMessage(lineUserId, userMessage);
                if (replyText != null && !replyText.trim().isEmpty()) {
                    messagingApiClient.replyMessage(new ReplyMessageRequest(
                            replyToken, List.of(new TextMessage(replyText)), false
                    ));
                }
            } catch (Exception e) {
                log.error("❌ เกิดข้อผิดพลาดในการประมวลผลข้อความ: ", e);
                messagingApiClient.replyMessage(new ReplyMessageRequest(
                        replyToken, List.of(new TextMessage("ขออภัยครับ ระบบประมวลผลขัดข้องชั่วคราว รบกวนรอแอดมินสักครู่นะครับ 🛠️")), false
                ));
            }
        }

        // ==========================================
        // 3. กรณีลูกค้าส่ง "รูปภาพ"
        // ==========================================
        else if (event.message() instanceof ImageMessageContent) {
            lastImageReceivedTime.put(lineUserId, Instant.now());
            log.info("📸 ได้รับรูปภาพจาก userId: {} -> หน่วงเวลา 3 วิ", lineUserId);

            new Thread(() -> {
                try {
                    Thread.sleep(3000);

                    Instant lastTime = lastImageReceivedTime.get(lineUserId);
                    if (lastTime != null && Instant.now().minusMillis(2500).isAfter(lastTime)) {
                        lastImageReceivedTime.remove(lineUserId);
                        log.info("⏰ หมดเวลาหน่วง โยน [รูปภาพ] เข้า Flow -> userId: {}", lineUserId);

                        // ส่ง Keyword ไปหลอกให้ Flow รู้ว่าได้รับรูปแล้ว
                        String responseText = chatFlowManager.handleTextMessage(lineUserId, "[รูปภาพ]");

                        if (responseText != null && !responseText.isEmpty()) {
                            messagingApiClient.replyMessage(new ReplyMessageRequest(
                                    replyToken, List.of(new TextMessage(responseText)), false
                            ));
                        }
                    }
                } catch (InterruptedException e) {
                    log.error("Error during image wait", e);
                }
            }).start();
        }
    }

    // ==========================================
    // 🎯 รับ Event แอดมินกดปุ่ม (Postback)
    // ==========================================
    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        String postbackData = event.postback().data();
        log.info("🎯 แอดมินกดปุ่ม Postback Data: {}", postbackData);

        try {
            Map<String, String> dataMap = parsePostbackData(postbackData);
            String action = dataMap.get("action");
            String serviceName = dataMap.get("service");
            String targetUserId = dataMap.get("userId");

            if (targetUserId == null || action == null) return;

            String adminReplyMessage = "";
            String messageToCustomer = null;

            UserState state = userStateRepository.findByLineUserId(targetUserId).orElse(new UserState());
            state.setLineUserId(targetUserId);

            switch (action) {
                case "approve":
                case "approve_doc":
                case "approve_credit":
                    // 💡 เช็คว่าแอดมินกดอนุมัติในขั้นตอนไหน?
                    if ("ADMIN_PHOTO_CHECK".equals(state.getCurrentState())) {
                        // 1. กรณีอนุมัติ "รูปรอบเครื่อง"
                        adminReplyMessage = "✅ ตรวจสภาพผ่าน! บอทกำลังขอรูปตั้งค่าต่อครับ";

                        // ให้ Flow ไปที่สเต็ปขอรูปตั้งค่า
                        state.setCurrentState("STEP_9_APPROVED_PHOTO");
                        userStateRepository.save(state);

                        // กระตุ้นให้ Flow ส่งข้อความ + รูปตัวอย่าง ให้ลูกค้า
                        String nextStepMessage = chatFlowManager.handleTextMessage(targetUserId, "continue");
                        if (nextStepMessage != null) {
                            messageToCustomer = nextStepMessage; // ใช้ข้อความจาก Flow ส่งให้ลูกค้า
                        }
                    } else {
                        // 2. กรณีอนุมัติ "ขั้นสุดท้าย" (ประเมินเครดิตและส่งราคา)
                        adminReplyMessage = "✅ อนุมัติเคสผ่านเรียบร้อย! ระบบส่งราคาให้ลูกค้าแล้วครับ";
                        messageToCustomer = "🎉 ยินดีด้วยครับ! ข้อมูลของคุณได้รับการอนุมัติเรียบร้อยแล้ว แอดมินจะรีบดำเนินการขั้นตอนต่อไปให้นะครับ";

                        state.setCurrentState("STEP_5_PRICING");
                        userStateRepository.save(state);

                        // กระตุ้นให้ Flow ส่งราคา
                        String nextStepMessage = chatFlowManager.handleTextMessage(targetUserId, "continue");
                        if (nextStepMessage != null) {
                            messageToCustomer += "\n\n" + nextStepMessage;
                        }
                    }
                    break;

                case "reject":
                case "reject_credit":
                    if ("ADMIN_PHOTO_CHECK".equals(state.getCurrentState())) {
                        // 1. ปฏิเสธเคสเพราะสภาพรูปเครื่องไม่ผ่าน
                        adminReplyMessage = "❌ ปฏิเสธสภาพเครื่องเรียบร้อยครับ (บอทแจ้งลูกค้าแล้ว)";
                        messageToCustomer = "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบรูปภาพ สภาพเครื่องยังไม่ตรงตามเงื่อนไขการรับเครื่องของทางร้านครับ หากมีข้อสงสัยสอบถามแอดมินเพิ่มเติมได้เลยครับ";
                    } else {
                        // 2. ปฏิเสธเคสขั้นสุดท้าย
                        adminReplyMessage = "❌ เคสนี้ถูกปฏิเสธเรียบร้อยครับ";
                        messageToCustomer = "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบข้อมูล ยังไม่ผ่านเกณฑ์การพิจารณาครับ หากมีข้อสงสัยสอบถามแอดมินได้เลยครับ";
                    }
                    state.setCurrentState("REJECTED");
                    userStateRepository.save(state);
                    break;

                case "take_case":
                    adminReplyMessage = "💬 รับเรื่องแล้ว! (ปิดบอทชั่วคราว) คุยกับลูกค้าต่อในแชท 1-on-1 ได้เลยครับ";
                    messageToCustomer = "แอดมินมารับเรื่องแล้วครับ! พิมพ์สอบถามได้เลยครับ 👇";
                    state.setCurrentState("ADMIN_MODE");
                    userStateRepository.save(state);
                    break;

                case "resume_bot":
                    adminReplyMessage = "▶️ เปิดบอทให้ดูแลลูกค้าคนนี้ต่อแล้วครับ";
                    messageToCustomer = "น้องทันใจ กลับมาดูแลต่อแล้วครับ! มีอะไรให้ช่วยบอกได้เลยครับ ✨";
                    state.setCurrentState(null); // ล้าง State ให้เริ่มใหม่
                    state.setServiceName(null);
                    userStateRepository.save(state);
                    break;
            }

            // ตอบแอดมินในกลุ่ม
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    event.replyToken(), List.of(new TextMessage(adminReplyMessage)), false
            ));

            // เด้งแจ้งลูกค้า
            if (messageToCustomer != null) {
                messagingApiClient.pushMessage(null, new PushMessageRequest(
                        targetUserId, List.of(new TextMessage(messageToCustomer)), false, (List<String>) null
                ));
            }

        } catch (Exception e) {
            log.error("❌ Error processing postback: ", e);
        }
    }

    private Map<String, String> parsePostbackData(String data) {
        Map<String, String> map = new HashMap<>();
        if (data == null || data.isEmpty()) return map;
        for (String pair : data.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private String getCustomerName(String userId) {
        try {
            return messagingApiClient.getProfile(userId).get().body().displayName();
        } catch (Exception e) {
            return "ลูกค้า";
        }
    }
}