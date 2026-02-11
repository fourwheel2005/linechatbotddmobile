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
import com.linecorp.bot.webhook.model.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Slf4j
@LineMessageHandler
public class LineBotHandler {

    private final OpenAiService openAiService;
    private final MessagingApiClient messagingApiClient;
    private final BotUserRepository botUserRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${line.admin.user-ids:}")
    private List<String> adminUserIds;

    // ========== Constants ==========
    private static final List<String> RESUME_KEYWORDS = Arrays.asList(
            "สภาพผ่าน", "รูปผ่านครับ", "เช็คแล้ว", "ผ่านครับ", "อนุมัติ", "บอททำงานต่อ", "resume", "ok"
    );

    private static final List<String> WAKE_WORDS = Arrays.asList(
            "สนใจ", "ราคา", "ผ่อน", "โปร", "ดาวน์", "เท่าไหร่", "กี่บาท", "ซื้อ",
            "iPhone", "iphone", "ไอโฟน", "12", "13", "14", "15", "16", "17",
            "ตาราง", "เอกสาร", "คนละครึ่ง", "สอบถาม"
    );

    // Pattern สำหรับตรวจจับชื่อ-นามสกุล (ต้องมีอักษรไทย 2 คำขึ้นไป)
    private static final Pattern NAME_PATTERN = Pattern.compile("^[ก-๏]+\\s+[ก-๏]+.*$");

    // สถานะต่างๆ (เพิ่ม WAIT_IMAGE ตาม Flow Chart)
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_WAIT_IMAGE = "WAIT_IMAGE";
    private static final String STATUS_WAIT_CREDIT = "WAIT_CREDIT";
    private static final String STATUS_WAIT_DOCS = "WAIT_DOCS";

    @Autowired
    public LineBotHandler(OpenAiService openAiService,
                          MessagingApiClient messagingApiClient,
                          BotUserRepository botUserRepository) {
        this.openAiService = openAiService;
        this.messagingApiClient = messagingApiClient;
        this.botUserRepository = botUserRepository;
    }

    // =========================================================================
    // Event 1: ลูกค้าใหม่กด "เพิ่มเพื่อน" (Add Friend)
    // =========================================================================
    @EventMapping
    public void handleFollow(FollowEvent event) {
        String userId = event.source().userId();
        BotUser newUser = createNewUser(userId);
        botUserRepository.save(newUser);
        reply(event.replyToken(), "สวัสดีครับ! 👋 น้องดีดี ยินดีให้บริการครับ");
        log.info("New friend added: {}", userId);
    }

    // =========================================================================
    // Event 2: มีข้อความเข้า (Text / Image)
    // =========================================================================
    @EventMapping
    public void handleEvent(MessageEvent event) {
        try {
            String userId = event.source().userId();
            String replyToken = event.replyToken();

            // ------------------------------------------------------------------
            // 🔐 Admin Remote Control
            // ------------------------------------------------------------------
            if (isAdminCommand(event, userId, replyToken)) {
                return; // จัดการเสร็จแล้ว
            }

            // ------------------------------------------------------------------
            // 👤 Load or Create User
            // ------------------------------------------------------------------
            BotUser botUser = loadOrCreateUser(userId);
            List<Map<String, String>> currentHistory = parseHistory(botUser.getChatHistoryJson());

            // ------------------------------------------------------------------
            // 📝 Text Message Handling
            // ------------------------------------------------------------------
            if (event.message() instanceof TextMessageContent textContent) {
                handleTextMessage(textContent, botUser, currentHistory, replyToken, userId);
            }
            // ------------------------------------------------------------------
            // 📸 Image Message Handling
            // ------------------------------------------------------------------
            else if (event.message() instanceof ImageMessageContent) {
                handleImageMessage(botUser, currentHistory, replyToken, userId);
            }

        } catch (Exception e) {
            log.error("Error in LineBotHandler", e);
        }
    }

    // =========================================================================
    // Event 3: จัดการปุ่ม Postback (Admin กดอนุมัติ/ปฏิเสธ)
    // =========================================================================
    @EventMapping
    public void handlePostback(PostbackEvent event) {
        try {
            String data = event.postback().data();
            String replyToken = event.replyToken();
            String adminId = event.source().userId();

            if (data.startsWith("resume:") || data.startsWith("reject:")) {
                handleAdminApproval(data, replyToken, adminId);
            }
        } catch (Exception e) {
            log.error("Error in handlePostback", e);
            reply(event.replyToken(), "⚠️ Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // 🔹 Text Message Handler (แยกออกมาเพื่อความชัดเจน)
    // =========================================================================
    private void handleTextMessage(TextMessageContent textContent, BotUser botUser,
                                   List<Map<String, String>> currentHistory,
                                   String replyToken, String userId) throws Exception {
        String userText = textContent.text().trim();

        // ========== 1. Special Commands ==========
        if (handleSpecialCommands(userText, botUser, replyToken, userId)) {
            return;
        }

        // ========== 2. Status Lock Guards (ตรวจเช็ค 1 จุดเดียว) ==========
        if (handleStatusLocks(botUser, replyToken)) {
            return;
        }

        // ========== 3. Human Mode Logic ==========
        if (botUser.isHumanMode() && !handleHumanMode(userText, botUser)) {
            return; // เงียบ (Human Mode)
        }

        // ========== 4. ตรวจจับชื่อ-นามสกุล (ถ้าอยู่ในจังหวะที่ถูก) ==========
        if (isWaitingForFullName(currentHistory) && isValidFullName(userText)) {
            handleFullNameSubmission(userText, botUser, currentHistory, replyToken);
            return;
        }

        // ========== 5. AI Processing ==========
        currentHistory.add(Map.of("role", "user", "content", userText));
        trimHistory(currentHistory);

        String aiResponse = openAiService.getChatGptResponse(currentHistory);
        String cleanResponse = cleanAiResponse(aiResponse);

        // ========== 6. Update Status Based on AI Response ==========
        updateStatusFromAiResponse(cleanResponse, botUser);

        // ========== 7. Save & Reply ==========
        currentHistory.add(Map.of("role", "assistant", "content", cleanResponse));
        botUser.setChatHistoryJson(objectMapper.writeValueAsString(currentHistory));
        botUserRepository.save(botUser);

        // ========== 8. Handle [CALL_ADMIN] Signal ==========
        if (aiResponse.contains("[CALL_ADMIN]")) {
            reply(replyToken, cleanResponse);
            activateHumanMode(botUser, "ลูกค้าสอบถามเรื่องซับซ้อน: " + userText);
        } else {
            reply(replyToken, aiResponse);
        }
    }

    // =========================================================================
    // 🔹 Image Message Handler
    // =========================================================================
    private void handleImageMessage(BotUser botUser, List<Map<String, String>> currentHistory,
                                    String replyToken, String userId) throws Exception {

        String currentStatus = botUser.getCurrentStatus();

        // ตรวจสอบว่าอยู่ในสถานะใด (ใช้ Status เป็นหลัก แทนการเช็ค History)
        switch (currentStatus) {
            case STATUS_WAIT_DOCS -> {
                // Step 7: ส่งเอกสาร -> โยนให้คนทันที (ไม่มีปุ่ม)
                log.info("User {} sent documents (Step 7). Direct handoff to admin.", userId);
                botUser.setHumanMode(true);
                botUser.setCurrentStatus(STATUS_NORMAL);
                botUser.setLastActiveTime(LocalDateTime.now());
                botUserRepository.save(botUser);

                reply(replyToken, "ได้รับเอกสารเรียบร้อยครับ! 📄✨ รบกวนรอแอดมินตรวจสอบและสรุปผลในแชทนี้สักครู่นะครับ");
                notifyAdminDirectly(botUser, "📄 ลูกค้าส่งเอกสาร (Statement/รูปงาน) แล้วครับ เข้าไปปิดการขายได้เลย!");
            }
            case STATUS_WAIT_IMAGE -> {
                // Step 2: ส่งรูปรอบเครื่อง -> ใช้ปุ่มอนุมัติ
                log.info("User {} sent device photos (Step 2). Waiting for admin approval.", userId);
                reply(replyToken, "ได้รับรูปภาพแล้วครับผม! 👍 รบกวนรอแอดมินตรวจสอบสภาพเครื่องสักครู่นะครับ");
                activateHumanMode(botUser, "📸 ลูกค้าส่งรูปภาพ (เช็คสภาพเครื่อง)");
            }
            default -> {
                // กรณีอื่นๆ (ไม่ควรเกิด แต่ป้องกันไว้)
                log.warn("User {} sent image in unexpected status: {}", userId, currentStatus);
                reply(replyToken, "ได้รับรูปภาพแล้วครับผม! 👍 รบกวนรอแอดมินตรวจสอบสักครู่นะครับ");
                activateHumanMode(botUser, "📸 ลูกค้าส่งรูปภาพ (สถานะ: " + currentStatus + ")");
            }
        }
    }

    // =========================================================================
    // 🔹 Admin Approval Handler
    // =========================================================================
    private void handleAdminApproval(String data, String replyToken, String adminId) throws Exception {
        boolean isApproved = data.startsWith("resume:");
        String[] parts = data.split(":");
        if (parts.length < 2) return;
        String targetUserId = parts[1];

        Optional<BotUser> targetUserOpt = botUserRepository.findById(targetUserId);
        if (targetUserOpt.isEmpty()) {
            reply(replyToken, "❌ ไม่พบข้อมูลลูกค้ารายนี้");
            return;
        }

        BotUser targetUser = targetUserOpt.get();

        // 🔍 เก็บสถานะเดิมไว้ก่อน (เพื่อให้ AI รู้บริบท)
        String previousStatus = targetUser.getCurrentStatus();

        // ปลดล็อคและรีเซ็ตสถานะ
        targetUser.setHumanMode(false);
        targetUser.setCurrentStatus(STATUS_NORMAL);
        botUserRepository.save(targetUser);

        // ตอบกลับ Admin
        String adminMsg = isApproved ? "✅ อนุมัติแล้ว" : "❌ ปฏิเสธแล้ว";
        reply(replyToken, adminMsg + " สำหรับลูกค้า " + targetUserId + " ครับ");

        // Trigger AI
        List<Map<String, String>> history = parseHistory(targetUser.getChatHistoryJson());
        trimHistory(history);

        // ✅ สร้างข้อความที่มี Context ชัดเจน
        String systemSignal = buildSystemSignalWithContext(isApproved, previousStatus, history);

        history.add(Map.of("role", "user", "content", systemSignal));

        String aiResponse = openAiService.getChatGptResponse(history);
        String finalResponse = cleanAiResponse(aiResponse);

        if (finalResponse.isEmpty()) {
            finalResponse = getFallbackResponse(isApproved, previousStatus);
        }

        history.add(Map.of("role", "assistant", "content", finalResponse));
        targetUser.setChatHistoryJson(objectMapper.writeValueAsString(history));
        botUserRepository.save(targetUser);

        // Push Message
        messagingApiClient.pushMessage(
                UUID.randomUUID(),
                new PushMessageRequest(targetUserId, List.of(new TextMessage(finalResponse)), false, null)
        );

        log.info("Admin {} action: {} on user {} (previous status: {})", adminId, adminMsg, targetUserId, previousStatus);
    }

    /**
     * สร้างข้อความสัญญาณให้ AI พร้อมบริบทที่ชัดเจน
     */
    private String buildSystemSignalWithContext(boolean isApproved, String previousStatus, List<Map<String, String>> history) {
        String approvalText = isApproved ? "อนุมัติแล้ว (Admin Approved)" : "ไม่ผ่าน (Admin Rejected)";

        // ดูว่าลูกค้าเพิ่งทำอะไรมา
        String lastUserMessage = getLastUserMessage(history);

        String signal;
        if (STATUS_WAIT_CREDIT.equals(previousStatus)) {
            // กรณี: เพิ่งส่งชื่อ-นามสกุลมา (Step 3.5)
            signal = String.format(
                    "แอดมินกดปุ่ม%s สำหรับการตรวจสอบเครดิต\n" +
                            "ลูกค้าเพิ่งแจ้งชื่อ: %s\n" +
                            "คำสั่ง: %s",
                    approvalText,
                    lastUserMessage,
                    isApproved ? "ดำเนินการต่อ Step 4 (แจ้งราคาโปรโมชั่นบอลลูน)" : "แจ้งว่าเครดิตไม่ผ่าน"
            );
            log.info("📋 Context: WAIT_CREDIT → Approved={}, Signal={}", isApproved, signal);
        } else if (STATUS_WAIT_IMAGE.equals(previousStatus)) {
            // กรณี: เพิ่งส่งรูปรอบเครื่องมา (Step 2)
            signal = String.format(
                    "แอดมินกดปุ่ม%s สำหรับการตรวจสอบสภาพเครื่อง\n" +
                            "คำสั่ง: %s",
                    approvalText,
                    isApproved ? "ดำเนินการต่อ Step 3 (ขอชื่อ-นามสกุล)" : "แจ้งว่าสภาพเครื่องไม่ผ่าน"
            );
            log.info("📋 Context: WAIT_IMAGE → Approved={}, Signal={}", isApproved, signal);
        } else {
            // กรณีทั่วไป (Fallback)
            signal = String.format("แอดมินกดปุ่ม%s", approvalText);
            log.warn("⚠️ Unknown previous status: {} → Using generic signal", previousStatus);
        }

        return signal;
    }

    /**
     * หาข้อความล่าสุดที่ลูกค้าพิมพ์มา
     */
    private String getLastUserMessage(List<Map<String, String>> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return msg.get("content");
            }
        }
        return "(ไม่มีข้อมูล)";
    }

    /**
     * สร้างข้อความ Fallback ตามสถานะ
     */
    private String getFallbackResponse(boolean isApproved, String previousStatus) {
        if (!isApproved) {
            return "ต้องขออภัยด้วยครับ ข้อมูลยังไม่ผ่านเกณฑ์พิจารณาครับ 🙏";
        }

        if (STATUS_WAIT_CREDIT.equals(previousStatus)) {
            return "ยินดีด้วยครับ เครดิตผ่านครับ! 🎉 กำลังสรุปยอดผ่อนให้สักครู่นะครับ...";
        } else if (STATUS_WAIT_IMAGE.equals(previousStatus)) {
            return "โอเคครับ สภาพเครื่องเบื้องต้นผ่านครับ ✅ รบกวนขอทราบ **ชื่อ-นามสกุล** ของลูกค้าด้วยนะครับผม (เพื่อเช็คเครดิตร้านสักครู่ครับ)";
        } else {
            return "ยินดีด้วยครับ! 🎉";
        }
    }

    // =========================================================================
    // 🛡️ Helper Methods - Guards & Validators
    // =========================================================================

    private boolean isAdminCommand(MessageEvent event, String userId, String replyToken) {
        if (!(event.message() instanceof TextMessageContent adminText) || !adminUserIds.contains(userId)) {
            return false;
        }

        String command = adminText.text().trim();
        if (command.startsWith("reset ")) {
            String targetId = command.split(" ")[1];
            Optional<BotUser> targetOpt = botUserRepository.findById(targetId);
            if (targetOpt.isPresent()) {
                BotUser t = targetOpt.get();
                t.setHumanMode(false);
                t.setChatHistoryJson("[]");
                t.setCurrentStatus(STATUS_NORMAL);
                botUserRepository.save(t);
                reply(replyToken, "✅ Reset ลูกค้า " + targetId + " เรียบร้อยครับ");
            } else {
                reply(replyToken, "❌ ไม่พบ UserID นี้ในระบบ");
            }
            return true;
        }
        return false;
    }

    private boolean handleSpecialCommands(String userText, BotUser botUser, String replyToken, String userId) {
        if ("#myid".equalsIgnoreCase(userText)) {
            reply(replyToken, "🆔 UserID: " + userId);
            return true;
        }

        if ("bot_start".equalsIgnoreCase(userText) || "#reset".equalsIgnoreCase(userText)) {
            botUser.setHumanMode(false);
            botUser.setChatHistoryJson("[]");
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUserRepository.save(botUser);
            reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (Reset Complete)");
            return true;
        }

        return false;
    }

    private boolean handleStatusLocks(BotUser botUser, String replyToken) {
        String status = botUser.getCurrentStatus();

        switch (status) {
            case STATUS_WAIT_CREDIT -> {
                reply(replyToken, "ใจเย็นๆ น้าา 😅 ระบบล็อคอยู่จนกว่าแอดมินจะกดอนุมัติครับ \n(รบกวนรอสักครู่นะครับ แอดมินกำลังรีบเช็คให้ครับ 🙏)");
                return true;
            }
            case STATUS_WAIT_DOCS -> {
                reply(replyToken, "⚠️ รบกวนลูกค้าส่งข้อมูลเป็น \"รูปภาพ\" เท่านั้นนะครับ 📸\n(เช่น รูปแคปหน้าจอ Statement หรือ รูปถ่ายตอนทำงานครับ)");
                return true;
            }
            case STATUS_WAIT_IMAGE -> {
                reply(replyToken, "⚠️ รบกวนลูกค้าส่งข้อมูลเป็น \"รูปภาพ\" เท่านั้นนะครับ 📸\n(รูปรอบเครื่อง + หน้า Settings > General > About ครับ)");
                return true;
            }
        }

        return false;
    }

    private boolean handleHumanMode(String userText, BotUser botUser) {
        // เช็คคำสั่ง Resume
        if (RESUME_KEYWORDS.stream().anyMatch(userText::contains)) {
            log.info("Resume command detected from user {}. Switching back to AI.", botUser.getUserId());
            botUser.setHumanMode(false);
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUserRepository.save(botUser);
            return true; // ให้ AI ประมวลผลต่อ
        }

        // เช็ค Wake Words
        if (WAKE_WORDS.stream().anyMatch(userText::contains)) {
            log.info("Wake word detected! Switching user {} to AI mode.", botUser.getUserId());
            botUser.setHumanMode(false);
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUserRepository.save(botUser);
            return true; // ให้ AI ประมวลผลต่อ
        }

        return false; // เงียบ (Human Mode)
    }

    private boolean isWaitingForFullName(List<Map<String, String>> history) {
        if (history.isEmpty() || history.size() < 2) return false;

        Map<String, String> lastMsg = history.get(history.size() - 1);
        if (!"assistant".equals(lastMsg.get("role"))) return false;

        String content = lastMsg.get("content").toLowerCase();
        return content.contains("ชื่อ") && content.contains("นามสกุล");
    }

    private boolean isValidFullName(String text) {
        // เช็คว่าเป็นชื่อ-นามสกุล (ไทย) จริงๆ
        return NAME_PATTERN.matcher(text).matches()
                && !text.contains("สนใจ")
                && !text.contains("ตกลง")
                && !text.contains("ครับ")
                && !text.contains("ค่ะ");
    }

    private void handleFullNameSubmission(String fullName, BotUser botUser,
                                          List<Map<String, String>> history,
                                          String replyToken) throws Exception {
        // เพิ่มข้อความของลูกค้าเข้า History
        history.add(Map.of("role", "user", "content", fullName));

        // ✅ เพิ่มบริบทให้ AI รู้ว่านี่คือการแจ้งชื่อ
        history.add(Map.of("role", "assistant", "content",
                "รับชื่อ-นามสกุล: " + fullName + " เรียบร้อยครับ กำลังส่งตรวจสอบเครดิตให้แอดมิน..."));

        // ล็อคสถานะเป็น WAIT_CREDIT
        botUser.setCurrentStatus(STATUS_WAIT_CREDIT);
        botUser.setChatHistoryJson(objectMapper.writeValueAsString(history));
        botUserRepository.save(botUser);

        // ตอบลูกค้า
        reply(replyToken, "รับข้อมูลเรียบร้อยครับ รบกวนรอแอดมินเช็คเครดิตสักครู่นะครับ ⏳");

        // แจ้งเตือนแอดมิน
        activateHumanMode(botUser, "ลูกค้าแจ้งชื่อ: " + fullName + " (รอเช็คเครดิต)");
    }

    private void updateStatusFromAiResponse(String aiResponse, BotUser botUser) {
        // ตรวจจับว่า AI ขอรูปรอบเครื่องหรือไม่
        if (aiResponse.contains("รบกวนลูกค้าถ่ายรูปรอบเครื่อง") || aiResponse.contains("Settings > General > About")) {
            botUser.setCurrentStatus(STATUS_WAIT_IMAGE);
            log.info("Status changed to WAIT_IMAGE for user {}", botUser.getUserId());
        }
        // ตรวจจับว่า AI ขอเอกสารหรือไม่
        else if (aiResponse.contains("สเตทเม้น") || aiResponse.contains("เอกสาร") || aiResponse.contains("รูปหน้าร้าน")) {
            botUser.setCurrentStatus(STATUS_WAIT_DOCS);
            log.info("Status changed to WAIT_DOCS for user {}", botUser.getUserId());
        }
    }

    private String cleanAiResponse(String aiResponse) {
        String cleaned = aiResponse.replace("[CALL_ADMIN]", "").trim();
        return cleaned.isEmpty() ? "สักครู่นะครับ แอดมินกำลังตรวจสอบข้อมูล" : cleaned;
    }

    private void trimHistory(List<Map<String, String>> history) {
        if (history.size() > 10) {
            history.subList(0, history.size() - 10).clear();
        }
    }

    // =========================================================================
    // 🔧 Utility Methods
    // =========================================================================

    private BotUser createNewUser(String userId) {
        BotUser user = new BotUser();
        user.setUserId(userId);
        user.setHumanMode(false);
        user.setChatHistoryJson("[]");
        user.setLastActiveTime(LocalDateTime.now());
        user.setCurrentStatus(STATUS_NORMAL);
        return user;
    }

    private BotUser loadOrCreateUser(String userId) {
        BotUser user = botUserRepository.findById(userId)
                .orElseGet(() -> {
                    BotUser oldUser = createNewUser(userId);
                    oldUser.setHumanMode(true); // Default เป็นคนสำหรับลูกค้าเก่า
                    botUserRepository.save(oldUser);
                    return oldUser;
                });

        user.setLastActiveTime(LocalDateTime.now());
        botUserRepository.save(user);
        return user;
    }

    private void activateHumanMode(BotUser botUser, String reason) {
        botUser.setHumanMode(true);
        botUser.setLastActiveTime(LocalDateTime.now());
        botUserRepository.save(botUser);

        if (adminUserIds != null && !adminUserIds.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                try {
                    var profile = messagingApiClient.getProfile(botUser.getUserId()).get();
                    List<Message> messages = getMessagesWithButtons(botUser, reason, profile);
                    MulticastRequest multicastRequest = new MulticastRequest(messages, adminUserIds, false, null);
                    messagingApiClient.multicast(UUID.randomUUID(), multicastRequest).join();
                    log.info("Notified admins (Button Mode) about user {}", botUser.getUserId());
                } catch (Exception e) {
                    log.error("Failed to notify admins asynchronously", e);
                }
            });
        }
    }

    private void notifyAdminDirectly(BotUser botUser, String messageText) {
        if (adminUserIds == null || adminUserIds.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                var profile = messagingApiClient.getProfile(botUser.getUserId()).get();
                String displayName = profile.body().displayName();
                String alertMsg = "🔔 " + messageText + "\n👤 ลูกค้า: " + displayName + "\n🆔 UserID: " + botUser.getUserId();
                TextMessage textMessage = new TextMessage(alertMsg);
                MulticastRequest multicastRequest = new MulticastRequest(List.of(textMessage), adminUserIds, false, null);
                messagingApiClient.multicast(UUID.randomUUID(), multicastRequest).join();
                log.info("Notified admins (Direct Mode) about user {}", botUser.getUserId());
            } catch (Exception e) {
                log.error("Failed to notify admins directly", e);
            }
        });
    }

    private static List<Message> getMessagesWithButtons(BotUser botUser, String reason, Result<UserProfileResponse> profile) {
        String displayName = profile.body().displayName();
        URI pictureUrl = profile.body().pictureUrl();

        PostbackAction approveAction = new PostbackAction(
                "✅ อนุมัติ / ผ่าน", "resume:" + botUser.getUserId(), "อนุมัติลูกค้า: " + displayName, null, null, null
        );
        PostbackAction rejectAction = new PostbackAction(
                "❌ ไม่ผ่าน", "reject:" + botUser.getUserId(), "ไม่อนุมัติลูกค้า: " + displayName, null, null, null
        );

        ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                pictureUrl, null, null, null, "ลูกค้ารอตรวจสภาพ/เครดิต", "สาเหตุ: " + reason, null,
                Arrays.asList(approveAction, rejectAction)
        );

        TemplateMessage templateMessage = new TemplateMessage("มีงานเข้า! ลูกค้ารอตรวจ", buttonsTemplate);
        TextMessage backupText = new TextMessage("🆔 UserID: " + botUser.getUserId());

        return Arrays.asList(templateMessage, backupText);
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