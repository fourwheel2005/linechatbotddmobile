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

    // ✅ เพิ่มตัวแปรนี้: เอาไว้กัน Spam แจ้งเตือน (Cool-down Map)
    private final Map<String, LocalDateTime> notifyCooldownMap = new java.util.concurrent.ConcurrentHashMap<>();

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

    private final Map<String, LocalDateTime> userReplyCooldownMap = new java.util.concurrent.ConcurrentHashMap<>();

    // สถานะต่างๆ
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_WAIT_IMAGE = "WAIT_IMAGE";
    private static final String STATUS_WAIT_CREDIT = "WAIT_CREDIT";
    private static final String STATUS_WAIT_DOCS = "WAIT_DOCS";
    private static final String STATUS_WAIT_SIGNOUT = "WAIT_SIGNOUT";


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
    // 🔹 Text Message Handler (ฉบับสมบูรณ์: รองรับ Flow 15 วัน + รูปกฎเหล็ก)
    // =========================================================================
    private void handleTextMessage(TextMessageContent textContent, BotUser botUser,
                                   List<Map<String, String>> currentHistory,
                                   String replyToken, String userId) throws Exception {
        String userText = textContent.text().trim();

        // ========== 1. Special Commands (คำสั่งพิเศษ / Reset) ==========
        if (userText.equalsIgnoreCase("#reset") || userText.equalsIgnoreCase("bot_start")) {
            botUser.setHumanMode(false);
            botUser.setChatHistoryJson("[]");
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUser.setHandlerAdminId(null); // ✅ ล้างชื่อแอดมินที่รับงาน

            // (Optional) ล้าง Cooldown แจ้งเตือน ถ้ามีตัวแปรนี้
            // if (notifyCooldownMap != null) notifyCooldownMap.remove(userId);

            botUserRepository.save(botUser);
            reply(replyToken, "🤖 น้องดีดี (AI) กลับมาประจำการแล้วครับ! (Reset Complete)");
            return;
        }

        if (handleSpecialCommands(userText, botUser, replyToken, userId)) {
            return;
        }

        // ========== 2. Status Lock Guards (ล็อคสถานะรอรูป/รอแอดมิน) ==========
        if (handleStatusLocks(botUser, replyToken)) {
            return;
        }

        // ========== 3. Human Mode Logic (โหมดคนตอบ) ==========
        if (botUser.isHumanMode() && !handleHumanMode(userText, botUser)) {
            return; // เงียบ (ปล่อยให้แอดมินตอบเอง)
        }

        // ========== 4. ตรวจจับชื่อ-นามสกุล (เฉพาะตอนขอชื่อเช็คเครดิต) ==========
        if (isWaitingForFullName(currentHistory) && isValidFullName(userText)) {
            handleFullNameSubmission(userText, botUser, currentHistory, replyToken);
            return;
        }

        // ========== 5. AI Processing ==========

        // 5.1 Add User Input
        currentHistory.add(Map.of("role", "user", "content", userText));

        // 5.2 Trim History (เก็บ 100 ข้อความ เพื่อกันลืม)
        trimHistory(currentHistory);

        // 5.3 Call OpenAI
        String aiResponse = openAiService.getChatGptResponse(currentHistory);

        // Clean Response: ลบ Tag คำสั่งออกจากข้อความที่จะบันทึก
        String cleanResponse = cleanAiResponse(aiResponse)
                .replace("[SHOW_HOWTO_IMAGE]", "")
                .replace("[SHOW_HOWTO_IMAGE_2]","")
                .replace("[SHOW_15DAY_RULES]", "")
                .replace("[SHOW_DOCS_MONTHLY]", "")
                .trim();

        // ========== 6. Update Status Based on AI Response ==========
        updateStatusFromAiResponse(cleanResponse, botUser);

        // ========== 7. Save History ==========
        // บันทึกคำตอบ AI ลง DB
        currentHistory.add(Map.of("role", "assistant", "content", cleanResponse));
        botUser.setChatHistoryJson(objectMapper.writeValueAsString(currentHistory));
        botUserRepository.save(botUser);

        // ========== 8. Handle Output & Signals (จัดการการตอบกลับ) ==========

        // 🔴 กรณี A: AI ส่งสัญญาณเรียกแอดมิน (จบการขาย / ลูกค้างง / ขอส่วนลด)
        if (aiResponse.contains("[CALL_ADMIN]")) {
            reply(replyToken, cleanResponse);

            // แจ้งเตือนแอดมิน (เข้าสู่โหมด Human)
            notifyAdminDirectly(botUser, "🚨 ลูกค้าแจ้งเรื่องสำคัญ / ส่งเอกสารครบแล้ว / ต้องการคุยกับคน");
            activateHumanMode(botUser, "AI ส่งไม้ต่อ: " + userText);
        }

        // 🟡 กรณี B: AI สั่งให้โชว์รูป How-To (Step 5A - รายเดือน: สอน Backup/Reset)
        else if (aiResponse.contains("[SHOW_HOWTO_IMAGE]")) {
            // URL รูปภาพสำหรับสอน Backup/Reset
            List<String> imageUrls = Arrays.asList(
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8495111.jpg", // วิธี Reset
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8495117.jpg", // วิธี Backup
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8519734.jpg"  // เอกสารที่ต้องใช้
            );
            sendCarouselMessage(replyToken, cleanResponse, "วิธีการเตรียมเครื่อง (รายเดือน)", imageUrls);
        }

        else if (aiResponse.contains("[SHOW_HOWTO_IMAGE_2]")) {
            // ลิงก์รูปภาพตัวอย่างเอกสาร (คุณสามารถเปลี่ยนเป็นลิงก์รูปที่ต้องการได้เลยครับ)
            String docImageUrl = "https://raw.githubusercontent.com/fourwheel2005/image/main/S__7593993.jpg";

            List<Message> messages = new ArrayList<>();
            // 1. ใส่ข้อความของ AI
            if (!cleanResponse.isEmpty()) {
                messages.add(new TextMessage(cleanResponse));
            }
            // 2. ใส่รูปภาพ
            messages.add(new ImageMessage(URI.create(docImageUrl), URI.create(docImageUrl)));

            // 3. ส่ง 2 อย่างไปพร้อมกันใน 1 การตอบกลับ
            replyMultiple(replyToken, messages);
        }

        // 🟢 กรณี C: AI สั่งให้โชว์กฎเหล็ก 15 วัน (Step 5B - ราย 15 วัน) 🔥 เพิ่มใหม่ตรงนี้
        else if (aiResponse.contains("[SHOW_15DAY_RULES]")) {
            // URL รูปภาพสำหรับ 15 วัน (เงื่อนไข, กฎเหล็ก, เอกสาร)
            List<String> imageUrls = Arrays.asList(
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8519737.jpg", // เงื่อนไขรีไฟแนนซ์
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8519739.jpg", // กฎเหล็ก 4 ข้อ
                    "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8519734.jpg"  // เอกสารที่ต้องใช้
            );
            sendCarouselMessage(replyToken, cleanResponse, "เงื่อนไขและกฎเหล็ก (15 วัน)", imageUrls);
        }

        // 🟠 [เพิ่มใหม่] กรณี E: AI สั่งให้ขอเอกสารรายเดือน (Step 6A) ส่งข้อความพร้อมรูปภาพ
        else if (aiResponse.contains("[SHOW_DOCS_MONTHLY]")) {
            // ลิงก์รูปภาพตัวอย่างเอกสาร (คุณสามารถเปลี่ยนเป็นลิงก์รูปที่ต้องการได้เลยครับ)
            String docImageUrl = "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8528063.jpg";

            List<Message> messages = new ArrayList<>();
            // 1. ใส่ข้อความของ AI
            if (!cleanResponse.isEmpty()) {
                messages.add(new TextMessage(cleanResponse));
            }
            // 2. ใส่รูปภาพ
            messages.add(new ImageMessage(URI.create(docImageUrl), URI.create(docImageUrl)));

            // 3. ส่ง 2 อย่างไปพร้อมกันใน 1 การตอบกลับ
            replyMultiple(replyToken, messages);
        }





        // 🔵 กรณี D: ตอบข้อความปกติ
        else {
            reply(replyToken, cleanResponse);
        }
    }

    // -------------------------------------------------------------------------
    // 🔧 Helper: ส่งข้อความพร้อม Image Carousel (แยกออกมาเพื่อความสะอาดของโค้ด)
    // -------------------------------------------------------------------------
    private void sendCarouselMessage(String replyToken, String textMsg, String altText, List<String> imageUrls) {
        try {
            // 1. สร้าง Columns
            List<ImageCarouselColumn> columns = new ArrayList<>();
            for (String url : imageUrls) {
                columns.add(new ImageCarouselColumn(
                        URI.create(url),
                        new URIAction("ดูรูปเต็ม", URI.create(url), null)
                ));
            }

            // 2. สร้าง Template
            ImageCarouselTemplate carouselTemplate = new ImageCarouselTemplate(columns);
            TemplateMessage imageMessage = new TemplateMessage(altText, carouselTemplate);

            // 3. แพ็คข้อความ (Text + Carousel)
            List<Message> messages = new ArrayList<>();
            if (textMsg != null && !textMsg.isEmpty()) {
                messages.add(new TextMessage(textMsg));
            }
            messages.add(imageMessage);

            // 4. ส่ง
            messagingApiClient.replyMessage(
                    new ReplyMessageRequest(replyToken, messages, false)
            ).get();

        } catch (Exception e) {
            log.error("Failed to send carousel message", e);
            // Fallback: ถ้าส่งรูปไม่ได้ ให้ส่งแค่ข้อความ
            reply(replyToken, textMsg);
        }
    }

    // =========================================================================
    // 🔹 Image Message Handler (แก้ไข: แจ้งเตือนแอดมินแค่ 1 ครั้ง ต่อ 1 นาที)
    // =========================================================================
    private void handleImageMessage(BotUser botUser, List<Map<String, String>> currentHistory,
                                    String replyToken, String userId) throws Exception {

        String currentStatus = botUser.getCurrentStatus();

        // อัปเดตเวลาล่าสุดที่ลูกค้า active
        botUser.setLastActiveTime(LocalDateTime.now());
        botUserRepository.save(botUser);

        // ==================================================================
        // 🛑 LOGIC กัน Spam ตอบกลับลูกค้า (User Reply Cooldown) 🛑
        // ==================================================================

        // 1. ดึงเวลาที่ตอบลูกค้าล่าสุด
        LocalDateTime lastReply = userReplyCooldownMap.get(userId);
        LocalDateTime now = LocalDateTime.now();

        // 2. เช็คว่าควรตอบไหม? (ถ้าไม่เคยตอบ หรือ ตอบไปนานเกิน 3 วินาทีแล้ว ให้ตอบใหม่)
        boolean shouldReplyUser = (lastReply == null) ||
                java.time.Duration.between(lastReply, now).toSeconds() > 3;

        if (shouldReplyUser) {
            // อัปเดตเวลาตอบล่าสุดทันที
            userReplyCooldownMap.put(userId, now);

            // เลือกข้อความตอบกลับตามสถานะ
            if (STATUS_WAIT_DOCS.equals(currentStatus)) {
                reply(replyToken, "ได้รับเอกสารแล้วครับ 📄 (ถ้าครบแล้วพิมพ์ 'ครบแล้ว' ได้เลยครับ)");
            } else if (STATUS_WAIT_SIGNOUT.equals(currentStatus)) {
                reply(replyToken, "ได้รับรูป Sign Out แล้วครับ รบกวนรอตรวจสอบสักครู่นะครับ ☁️⏳");
            } else {
                reply(replyToken, "ได้รับรูปภาพแล้วครับผม รอเเอดมินตรวจสอบซักครู่นะครับ! 👍");
            }
        } else {
            // 🤫 เงียบกริบ (ไม่ต้องตอบซ้ำ เพราะถือว่าเป็นรูปล็อตเดียวกัน)
            log.info("Skipped reply to user {} (Spam protection active)", userId);
        }

        // ==================================================================

        // --- Logic: แจ้งเตือน Admin (อันเดิมของคุณ ไม่ต้องแก้) ---
        // (ส่วนนี้ทำงานแยกกัน คือแม้จะไม่ตอบลูกค้า แต่ถ้าถึงเวลาเตือนแอดมิน ก็ยังเตือนปกติ)
        LocalDateTime lastNotify = notifyCooldownMap.get(userId);
        boolean shouldNotifyAdmin = (lastNotify == null) ||
                java.time.Duration.between(lastNotify, now).toSeconds() > 60;

        if (shouldNotifyAdmin) {
            notifyCooldownMap.put(userId, now);

            if (STATUS_WAIT_DOCS.equals(currentStatus)) {
                notifyAdminDirectly(botUser, "📄 ลูกค้าทยอยส่งเอกสารเข้ามาครับ");
            } else if (STATUS_WAIT_SIGNOUT.equals(currentStatus)) {
                activateHumanMode(botUser, "☁️ ลูกค้าส่งภาพ Sign Out iCloud เข้ามาครับ");
            } else if (STATUS_WAIT_IMAGE.equals(currentStatus)) {
                activateHumanMode(botUser, "📸 ลูกค้าส่งรูปภาพเข้ามา (เช็คสภาพเครื่อง)");
            } else {
                activateHumanMode(botUser, "📸 ลูกค้าส่งรูปภาพ (ไม่ได้อยู่ในสถานะรอรูป)");
            }
        }
    }

    // =========================================================================
    // 🔹 Admin Approval Handler (ฉบับสมบูรณ์: Lock งาน + ประกาศบอกทีม + ต่อบท AI)
    // =========================================================================
    private void handleAdminApproval(String data, String replyToken, String adminId) throws Exception {
        // 1. แยกข้อมูล (Format -> "resume:USER_ID" หรือ "reject:USER_ID")
        boolean isApproved = data.startsWith("resume:");
        String[] parts = data.split(":");
        if (parts.length < 2) return;
        String targetUserId = parts[1];

        // 2. ค้นหา User เป้าหมาย
        Optional<BotUser> targetUserOpt = botUserRepository.findById(targetUserId);
        if (targetUserOpt.isEmpty()) {
            reply(replyToken, "❌ ไม่พบข้อมูลลูกค้ารายนี้ (อาจถูกลบไปแล้ว)");
            return;
        }

        BotUser targetUser = targetUserOpt.get();

        // ------------------------------------------------------------------
        // 🔥 [CORE FEATURE] ระบบล็อคงาน & ประกาศบอกทีม (Team Broadcast)
        // ------------------------------------------------------------------
        String currentHandler = targetUser.getHandlerAdminId();

        // กรณี A: งานนี้มีเจ้าของแล้ว และ "ไม่ใช่เรา"
        if (currentHandler != null && !currentHandler.equals(adminId)) {
            // พยายามดึงชื่อคนที่เป็นเจ้าของเคสมาโชว์
            String ownerName = "แอดมินท่านอื่น";
            try {
                // (Optional) ถ้าดึงชื่อไม่ได้ก็ไม่เป็นไร
                var profile = messagingApiClient.getProfile(currentHandler).get();
                ownerName = profile.body().displayName();
            } catch (Exception e) { /* ignore */ }

            reply(replyToken, "⚠️ ช้าไปครับ! เคสนี้คุณ " + ownerName + " รับไปดูแลแล้วครับ 😅");
            return; // ⛔ จบการทำงานทันที (ห้ามไปต่อ)
        }

        // กรณี B: ยังไม่มีใครรับงาน -> เราเป็นคนแรก! (หรือเป็นเราเองที่กดซ้ำ)
        if (currentHandler == null) {
            // 1. ประทับตราจองงาน
            targetUser.setHandlerAdminId(adminId);
            botUserRepository.save(targetUser);

            // 2. ประกาศบอกแอดมินทุกคน (Broadcast) ว่า "จบนะ! ฉันรับแล้ว"
            try {
                // ดึงชื่อโปรไฟล์ของเราเอง
                String myName = "Unknown Admin";
                try {
                    var profile = messagingApiClient.getProfile(adminId).get();
                    myName = profile.body().displayName();
                } catch (Exception e) {
                    log.warn("Cannot get admin profile", e);
                }

                // สร้างข้อความประกาศ
                String lockMsg = "🔒 เคสลูกค้า (" + targetUserId.substring(0, 5) + "...) \n✅ ถูกรับงานแล้วโดย: " + myName;

                // ส่งหาแอดมินทุกคน (ยกเว้นเราเองก็ได้ แต่ส่งหมดง่ายกว่า)
                MulticastRequest notifyAll = new MulticastRequest(
                        List.of(new TextMessage(lockMsg)),
                        adminUserIds,
                        false, null
                );
                // ยิง Broadcast (ไม่ต้องรอผลลัพธ์)
                messagingApiClient.multicast(UUID.randomUUID(), notifyAll);

            } catch (Exception e) {
                log.error("Failed to broadcast lock status", e);
            }
        }
        // ------------------------------------------------------------------

        // 🔍 เก็บสถานะเดิมไว้ก่อน (เพื่อให้ AI รู้บริบทว่าเมื่อกี้ลูกค้าทำอะไรอยู่)
        String previousStatus = targetUser.getCurrentStatus();

        // 3. ปลดล็อคและรีเซ็ตสถานะลูกค้า
        targetUser.setHumanMode(false);             // คืนร่างให้ AI ตอบ
        targetUser.setCurrentStatus(STATUS_NORMAL); // เคลียร์สถานะรอตรวจสอบ

        // 🔥 [สำคัญ 1] ล้าง Cooldown แจ้งเตือนทันที (Reset Anti-Spam)
        // เพื่อให้ถ้ารูป/เอกสารใหม่เข้ามาหลังจากนี้ ระบบจะแจ้งเตือนแอดมินทันที
        if (notifyCooldownMap != null) {
            notifyCooldownMap.remove(targetUserId);
        }

        // 🔥 [สำคัญ 2] รีเซ็ตเวลา Active ย้อนหลัง (กันเหนียว)
        targetUser.setLastActiveTime(LocalDateTime.now().minusMinutes(10));

        botUserRepository.save(targetUser);

        // 4. ตอบกลับ Admin (เฉพาะคนกด) ให้รู้ว่าระบบรับคำสั่งแล้ว
        String adminMsg = isApproved ? "✅ อนุมัติแล้ว" : "❌ ปฏิเสธแล้ว";
        reply(replyToken, adminMsg + " กำลังแจ้งลูกค้าและให้ AI ทำงานต่อครับ...");

        // 5. เตรียมข้อมูลส่งให้ AI
        List<Map<String, String>> history = parseHistory(targetUser.getChatHistoryJson());
        trimHistory(history); // ตัดข้อความเก่าออก

        // ✅ สร้าง "System Signal" บอก AI ว่าแอดมินกดปุ่มอะไร (เพื่อให้ AI ไหลต่อถูก Step)d
        String systemSignal = buildSystemSignalWithContext(isApproved, previousStatus, history);

        // เพิ่ม Signal ลงในประวัติ (เสมือนเป็นสิ่งที่ระบบบอก AI แบบลับๆ)
        history.add(Map.of("role", "user", "content", systemSignal));

        // 6. เรียก AI ให้ประมวลผลคำตอบถัดไป
        String aiResponse = openAiService.getChatGptResponse(history);
        String finalResponse = cleanAiResponse(aiResponse);

        // กรณี AI เอ๋อ หรือตอบว่างเปล่า ให้ใช้ข้อความสำรอง (Fallback)
        if (finalResponse.isEmpty()) {
            finalResponse = getFallbackResponse(isApproved, previousStatus);
        }

        // 7. บันทึกคำตอบ AI ลงประวัติและ Save
        history.add(Map.of("role", "assistant", "content", finalResponse));
        targetUser.setChatHistoryJson(objectMapper.writeValueAsString(history));
        botUserRepository.save(targetUser);

        // 8. Push ข้อความไปหาลูกค้า (Customer)
        try {
            messagingApiClient.pushMessage(
                    UUID.randomUUID(),
                    new PushMessageRequest(targetUserId, List.of(new TextMessage(finalResponse)), false, null)
            ).get(); // ใช้ .get() เพื่อรอให้ส่งเสร็จ
        } catch (Exception e) {
            log.error("Failed to push message to user {}", targetUserId, e);
            reply(replyToken, "⚠️ แจ้งเตือน: อนุมัติในระบบแล้ว แต่ส่งข้อความหาลูกค้าไม่สำเร็จ (ลูกค้าอาจบล็อก)");
        }

        log.info("Admin {} action: {} on user {} (previous status: {})", adminId, adminMsg, targetUserId, previousStatus);
    }

    private String buildSystemSignalWithContext(boolean isApproved, String previousStatus, List<Map<String, String>> history) {
        String approvalText = isApproved ? "อนุมัติแล้ว (Admin Approved)" : "ไม่ผ่าน (Admin Rejected)";
        String lastUserMessage = getLastUserMessage(history);

        String signal;

        if (STATUS_WAIT_CREDIT.equals(previousStatus)) {
            signal = String.format(
                    "แอดมินกดปุ่ม%s สำหรับการตรวจสอบเครดิต\nลูกค้าเพิ่งแจ้งชื่อ: %s\nคำสั่ง: %s",
                    approvalText, lastUserMessage,
                    isApproved ? "ดำเนินการต่อ Step 4 (แจ้งราคาโปรโมชั่นบอลลูน)" : "แจ้งว่าเครดิตไม่ผ่าน"
            );
        }
        else if (STATUS_WAIT_IMAGE.equals(previousStatus)) {
            signal = String.format(
                    "แอดมินกดปุ่ม%s สำหรับการตรวจสอบสภาพเครื่อง\nคำสั่ง: %s",
                    approvalText,
                    isApproved ? "ดำเนินการต่อ Step 3 (ขอชื่อ-นามสกุล)" : "แจ้งว่าสภาพเครื่องไม่ผ่าน"
            );
        }
        // --- [เพิ่ม Case นี้เข้าไป] ---
        else if (STATUS_WAIT_SIGNOUT.equals(previousStatus)) {
            signal = String.format(
                    "แอดมินตรวจสอบรูปภาพแล้ว กดปุ่ม%s (ยืนยันว่าลูกค้า Sign Out เรียบร้อย)\nคำสั่ง: %s",
                    approvalText,
                    isApproved ? "ดำเนินการต่อ Step 6B (ขอเอกสารสำหรับ Flow 15 วัน)" : "แจ้งลูกค้าว่ารูป Sign Out ยังไม่ถูกต้อง ให้ลองใหม่"
            );
        }
        // ---------------------------
        else {
            signal = String.format("แอดมินกดปุ่ม%s", approvalText);
        }
        return signal;
    }

    private String getLastUserMessage(List<Map<String, String>> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> msg = history.get(i);
            if ("user".equals(msg.get("role"))) return msg.get("content");
        }
        return "(ไม่มีข้อมูล)";
    }

    private String getFallbackResponse(boolean isApproved, String previousStatus) {
        if (!isApproved) return "ต้องขออภัยด้วยครับ ข้อมูลยังไม่ผ่านเกณฑ์พิจารณาครับ 🙏";
        if (STATUS_WAIT_CREDIT.equals(previousStatus)) return "ยินดีด้วยครับ เครดิตผ่านครับ! 🎉 กำลังสรุปยอดผ่อนให้สักครู่นะครับ...";
        if (STATUS_WAIT_IMAGE.equals(previousStatus)) return "โอเคครับ สภาพเครื่องเบื้องต้นผ่านครับ ✅ รบกวนขอทราบ **ชื่อ-นามสกุล** ของลูกค้าด้วยนะครับผม";
        return "ยินดีด้วยครับ! 🎉";
    }

    // =========================================================================
    // 🛡️ Helper Methods
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
            case STATUS_WAIT_IMAGE -> {
                reply(replyToken, "⚠️ รบกวนลูกค้าส่งข้อมูลเป็น \"รูปภาพ\" เท่านั้นนะครับ 📸\n(รูปรอบเครื่อง + หน้า Settings > General > About ครับ)");
                return true;
            }
            // ปลดล็อค STATUS_WAIT_DOCS แล้ว เพื่อให้ลูกค้าพิมพ์ "ครบแล้ว" ได้
        }
        return false;
    }

    private boolean handleHumanMode(String userText, BotUser botUser) {
        if (RESUME_KEYWORDS.stream().anyMatch(userText::contains)) {
            log.info("Resume command detected from user {}. Switching back to AI.", botUser.getUserId());
            botUser.setHumanMode(false);
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUserRepository.save(botUser);
            return true;
        }
        if (WAKE_WORDS.stream().anyMatch(userText::contains)) {
            log.info("Wake word detected! Switching user {} to AI mode.", botUser.getUserId());
            botUser.setHumanMode(false);
            botUser.setCurrentStatus(STATUS_NORMAL);
            botUserRepository.save(botUser);
            return true;
        }
        return false;
    }

    private boolean isWaitingForFullName(List<Map<String, String>> history) {
        if (history.isEmpty() || history.size() < 2) return false;
        Map<String, String> lastMsg = history.get(history.size() - 1);
        if (!"assistant".equals(lastMsg.get("role"))) return false;
        String content = lastMsg.get("content").toLowerCase();
        return content.contains("ชื่อ") && content.contains("นามสกุล");
    }

    private boolean isValidFullName(String text) {
        String cleanText = text.replaceAll("(?i)(ครับ|ค่ะ|จ้า|คาบ|น้า)", "").trim();
        return NAME_PATTERN.matcher(cleanText).matches() && cleanText.length() > 5 && !cleanText.contains("สนใจ") && !cleanText.contains("ราคา");
    }

    private void handleFullNameSubmission(String fullName, BotUser botUser, List<Map<String, String>> history, String replyToken) throws Exception {
        history.add(Map.of("role", "user", "content", fullName));
        history.add(Map.of("role", "assistant", "content", "รับชื่อ-นามสกุล: " + fullName + " เรียบร้อยครับ กำลังส่งตรวจสอบเครดิตให้แอดมิน..."));
        botUser.setCurrentStatus(STATUS_WAIT_CREDIT);
        botUser.setChatHistoryJson(objectMapper.writeValueAsString(history));
        botUserRepository.save(botUser);
        reply(replyToken, "รับข้อมูลเรียบร้อยครับ รบกวนรอแอดมินเช็คเครดิตสักครู่นะครับ ⏳");
        activateHumanMode(botUser, "ลูกค้าแจ้งชื่อ: " + fullName + " (รอเช็คเครดิต)");
    }

    private void updateStatusFromAiResponse(String aiResponse, BotUser botUser) {
        // --- 1. เช็คกลุ่ม WAIT_DOCS (ส่งเอกสารทั้ง รายเดือน และ 15 วัน) ---
        if (aiResponse.contains("สเตทเม้น") ||        // รายเดือน
                aiResponse.contains("เอกสาร") ||
                aiResponse.contains("บัตรประชาชน") || // ทั้งคู่
                aiResponse.contains("รูปหน้าร้าน") ||
                aiResponse.contains("ข้อมูลทั้งหมด") ||

                // 🔥 [เพิ่มใหม่] Keywords เฉพาะของ Flow 15 วัน (Step 6B) 🔥
                aiResponse.contains("หน้าปก Facebook") ||
                aiResponse.contains("Battery Health") ||
                aiResponse.contains("รหัสหน้าจอ") ||
                aiResponse.contains("ลิ้งค์") ||       // ขอลิ้งค์ Facebook/Line
                aiResponse.contains("Selfie")) {

            botUser.setCurrentStatus(STATUS_WAIT_DOCS); // ✅ ให้เป็นสถานะรอเอกสารเหมือนกัน
            log.info("Status changed to WAIT_DOCS for user {}", botUser.getUserId());
        }

        // --- 2. เช็คกลุ่ม WAIT_SIGNOUT (ขั้นตอน Step 5B) ---
        else if (aiResponse.contains("Sign Out") ||
                aiResponse.contains("ลงชื่อออก") ||
                aiResponse.contains("หลักฐานการลบ")) {

            botUser.setCurrentStatus(STATUS_WAIT_SIGNOUT);
            log.info("Status changed to WAIT_SIGNOUT for user {}", botUser.getUserId());
        }

        // --- 3. เช็คกลุ่ม WAIT_IMAGE (เช็คสภาพทั่วไป / Step 2) ---
        else if (aiResponse.contains("ถ่ายรูปรอบเครื่อง") ||
                aiResponse.contains("Settings") ||
                aiResponse.contains("เช็คโมเดล")) {

            botUser.setCurrentStatus(STATUS_WAIT_IMAGE);
            log.info("Status changed to WAIT_IMAGE for user {}", botUser.getUserId());
        }
    }

    private String cleanAiResponse(String aiResponse) {
        String cleaned = aiResponse.replace("[CALL_ADMIN]", "").trim();
        return cleaned.isEmpty() ? "สักครู่นะครับ แอดมินกำลังตรวจสอบข้อมูล" : cleaned;
    }

    private void trimHistory(List<Map<String, String>> history) {
        if (history.size() > 50) history.subList(0, history.size() - 50).clear();
    }

    // =========================================================================
    // 🔧 Utility
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
        BotUser user = botUserRepository.findById(userId).orElseGet(() -> {
            BotUser oldUser = createNewUser(userId);
            oldUser.setHumanMode(true); // ตามโค้ดเดิมของคุณ
            return oldUser;
        });

        // 🌟 [เพิ่มโค้ดส่วนนี้] เช็คว่าถ้ายังไม่มีชื่อ ให้ไปดึงโปรไฟล์จาก LINE API มาอัปเดต
        if (user.getDisplayName() == null || user.getDisplayName().isEmpty()) {
            try {
                var profileResponse = messagingApiClient.getProfile(userId).get();
                if (profileResponse != null && profileResponse.body() != null) {
                    // เซฟชื่อไลน์
                    user.setDisplayName(profileResponse.body().displayName());

                    // เซฟรูปโปรไฟล์ (บางคนอาจจะไม่ได้ตั้งรูป ต้องเช็ค null ด้วย)
                    if (profileResponse.body().pictureUrl() != null) {
                        user.setPictureUrl(profileResponse.body().pictureUrl().toString());
                    }
                }
            } catch (Exception e) {
                log.error("ไม่สามารถดึงโปรไฟล์ LINE ของลูกค้าได้: {}", userId, e);
            }
        }

        // อัปเดตเวลาล่าสุดที่ใช้งาน
        user.setLastActiveTime(LocalDateTime.now());

        // บันทึกลง Database
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

    // =========================================================================
    // 🔹 Helper: สร้างปุ่มแจ้งเตือน Admin (ฉบับแก้ไข SDK v8 สมบูรณ์)
    // =========================================================================
    private static List<Message> getMessagesWithButtons(BotUser botUser, String reason, Result<UserProfileResponse> profile) {
        String displayName = profile.body().displayName();
        URI pictureUrl = profile.body().pictureUrl();
        String status = botUser.getCurrentStatus();

        String title;
        String detailText;
        String btnApproveLabel;
        String btnRejectLabel;

        switch (status) {
            case STATUS_WAIT_IMAGE -> {
                title = "📸 ตรวจสภาพเครื่อง";
                detailText = "ลูกค้าส่งรูปภาพเข้ามา\nสาเหตุ: " + reason;
                btnApproveLabel = "✅ สภาพผ่าน/สวย";
                btnRejectLabel = "❌ สภาพไม่ผ่าน";
            }
            case STATUS_WAIT_CREDIT -> {
                title = "💳 เช็คเครดิตลูกค้า";
                detailText = "ลูกค้าแจ้งชื่อ-นามสกุล\nสาเหตุ: " + reason;
                btnApproveLabel = "✅ เครดิตผ่าน";
                btnRejectLabel = "❌ ไม่ผ่าน/ติดBL";
            }
            // --- [เพิ่ม Case นี้เข้าไป] ---
            case STATUS_WAIT_SIGNOUT -> {
                title = "☁️ เช็ค Sign Out"; // หัวข้อปุ่ม
                detailText = "ลูกค้าส่งภาพ Sign Out iCloud\nสาเหตุ: " + reason;
                btnApproveLabel = "✅ Sign Out แล้ว"; // ปุ่มอนุมัติ
                btnRejectLabel = "❌ ยังไม่ผ่าน/มีเมล"; // ปุ่มปฏิเสธ
            }
            // ---------------------------
            default -> {
                title = "🆘 เรียกแอดมิน";
                detailText = "AI ส่งไม้ต่อให้คนดูแล\nสาเหตุ: " + reason;
                btnApproveLabel = "✅ รับเรื่องแล้ว";
                btnRejectLabel = "❌ ปฏิเสธ/จบงาน";
            }
        }

        // ตัดข้อความถ้าเกินกำหนดไลน์ (60 ตัว)
        if (detailText.length() > 60) {
            detailText = detailText.substring(0, 57) + "...";
        }
        if (title.length() > 40) {
            title = title.substring(0, 37) + "...";
        }

        PostbackAction approveAction = new PostbackAction(btnApproveLabel, "resume:" + botUser.getUserId(), "อนุมัติลูกค้า: " + displayName, null, null, null);
        PostbackAction rejectAction = new PostbackAction(btnRejectLabel, "reject:" + botUser.getUserId(), "ไม่อนุมัติลูกค้า: " + displayName, null, null, null);

        ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                pictureUrl, null, null, null,
                title, detailText, null,
                Arrays.asList(approveAction, rejectAction)
        );

        TemplateMessage templateMessage = new TemplateMessage("🔔 งานเข้า: " + title, buttonsTemplate);
        TextMessage backupText = new TextMessage("🆔 UserID: " + botUser.getUserId() + "\n👤 ลูกค้า: " + displayName);

        return Arrays.asList(templateMessage, backupText);
    }

    private void reply(String replyToken, String message) {
        try {
            messagingApiClient.replyMessage(new ReplyMessageRequest(replyToken, List.of(new TextMessage(message)), false)).get();
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

    private String sanitizeAndReinforce(String input) {
        // 1. ลบคำสั่งเสี่ยง (Basic)
        String cleanInput = input.replaceAll("(?i)(ignore|forget|system prompt|instruction)", "");

        // 2. จำกัดความยาว (ป้องกัน Token บวม)
        if (cleanInput.length() > 500) cleanInput = cleanInput.substring(0, 500);

        return cleanInput;
    }

    private void replyMultiple(String replyToken, List<Message> messages) {
        try {
            messagingApiClient.replyMessage(new ReplyMessageRequest(replyToken, messages, false)).get();
        } catch (Exception e) {
            log.error("Reply multiple messages failed", e);
        }
    }

}