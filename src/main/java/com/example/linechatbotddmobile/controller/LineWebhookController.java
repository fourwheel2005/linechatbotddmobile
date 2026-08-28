package com.example.linechatbotddmobile.controller; // ปรับให้ตรงกับ package ของคุณ

import com.example.linechatbotddmobile.config.AdminGroup;
import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.line.ChatFlowManager; // ปรับให้ตรง
import com.example.linechatbotddmobile.service.line.UserConversationLockService;
import com.example.linechatbotddmobile.service.line.UserStateService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@LineMessageHandler
public class LineWebhookController {

    private final ChatFlowManager chatFlowManager;
    private final MessagingApiClient messagingApiClient;
    private final UserStateRepository userStateRepository;
    private final com.example.linechatbotddmobile.service.line.LineMessageService lineMessageService;
    private final com.example.linechatbotddmobile.service.line.LineProfileService lineProfileService;
    private final com.example.linechatbotddmobile.service.line.WebhookIdempotencyService webhookIdempotencyService;
    private final UserStateService userStateService;
    private final UserConversationLockService conversationLockService;

    // ตัวแปรสำหรับหน่วงเวลาการรับรูปภาพ
    private final ConcurrentHashMap<String, Instant> lastImageReceivedTime = new ConcurrentHashMap<>();

    // 🧵 Thread pool มีขอบเขตสำหรับ debounce รูปภาพ
    // แทน new Thread() ต่อรูป 1 ใบ (ป้องกัน thread/connection ระเบิดเมื่อลูกค้าส่งรูปรัวๆ)
    private static final int IMAGE_BATCH_POOL_SIZE = 4;
    private final ExecutorService imageBatchExecutor = Executors.newFixedThreadPool(
            IMAGE_BATCH_POOL_SIZE,
            runnable -> {
                Thread thread = new Thread(runnable, "image-batch-worker");
                thread.setDaemon(true);
                return thread;
            });

    // ⚡ Worker pool มีขอบเขต สำหรับประมวลผลข้อความ/พิกัดของลูกค้า (flow + AI)
    // เดิมงานหนักรันบน thread ของ webhook (Tomcat) โดยตรง → thread ถูกถือค้างตลอดช่วงรอ AI/DB
    // พอ traffic เยอะ thread หมด → webhook รับงานใหม่ไม่ได้ = บอท "ไม่เห็นการกดปุ่ม" จนต้อง restart
    // ย้ายมา pool แยกที่มี queue → คืน 200 ให้ LINE ทันที, thread ของ webhook ว่างเสมอ
    // ขนาด max = 20 ให้สอดคล้องกับ Hikari pool (20) เพราะแต่ละงานยืม DB connection แค่สั้นๆ ตอนแตะ DB
    private static final int MSG_POOL_CORE = 8;
    private static final int MSG_POOL_MAX = 20;
    private static final int MSG_QUEUE_CAPACITY = 200;
    private final ExecutorService messageProcessingExecutor = new ThreadPoolExecutor(
            MSG_POOL_CORE, MSG_POOL_MAX, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MSG_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "msg-worker");
                thread.setDaemon(true);
                return thread;
            },
            // งานล้น (queue เต็ม + thread เต็ม) → ให้ thread ที่เรียกรันเองเป็น backpressure แทนการทิ้งงานลูกค้า
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    void shutdownExecutors() {
        imageBatchExecutor.shutdown();
        messageProcessingExecutor.shutdown();
    }

    // เวลาในการรอรับรูปต่อเนื่อง (กันลูกค้าส่งรูปหลายใบติด)
    private static final long IMAGE_BATCH_WAIT_MS = 3000L;
    private static final long IMAGE_BATCH_THRESHOLD_MS = 2500L;

    // ID ของกลุ่มแอดมินสำหรับเคส panic (ลูกค้าขอคุยกับคนโดยตรง / กดปุ่ม "คุยกับแอดมิน")
    // ตั้งใจแยกจากกลุ่มของ flow — ดูรายละเอียดที่ AdminGroup
    private final String PANIC_ADMIN_GROUP_ID = AdminGroup.PANIC_GROUP_ID;

    // บริการเดียวที่มี flow อัตโนมัติ — ใช้เติมกลับตอนแอดมินคืนบอทให้ลูกค้า
    private static final String BALLOON_SERVICE = "ผ่อนบอลลูน";

    // ==========================================
    // 🎉 รับ Event ลูกค้า Add Friend ใหม่
    // ==========================================
    @EventMapping
    public void handleFollowEvent(FollowEvent event) {
        if (!webhookIdempotencyService.markAsProcessed(event.webhookEventId())) {
            return;
        }
        String lineUserId = event.source().userId();
        log.info("🎉 มีลูกค้าแอดเพื่อนใหม่: {}", lineUserId);

        // ยิง Flex Message ต้อนรับไปหาลูกค้า
        lineMessageService.sendWelcomeCard(lineUserId);
    }

    // ==========================================
    // 👋 รับ Event ลูกค้า Unfollow / Block บอท
    // ==========================================
    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {
        if (!webhookIdempotencyService.markAsProcessed(event.webhookEventId())) {
            return;
        }
        log.info("👋 ลูกค้า unfollow/block บอท: userId={}", event.source().userId());
        // ไม่ต้องตอบกลับ — replyToken จะใช้ไม่ได้อยู่แล้วเพราะลูกค้าบล็อกแล้ว
    }

    // ==========================================
    // 🛡️ Catch-all สำหรับ event อื่นๆ ที่ LINE อาจเพิ่มในอนาคต
    // (กัน UnsupportedOperationException ทำให้ LINE retry 4 ครั้ง)
    // ==========================================
    @EventMapping
    public void handleDefaultEvent(Event event) {
        log.info("ℹ️ ได้รับ event ที่ยังไม่ได้จัดการ: type={}, eventId={}",
                event.getClass().getSimpleName(),
                event.webhookEventId());
    }

    // ==========================================
    // ✉️ & 📸 รับ Event ข้อความและรูปภาพ
    // ==========================================
    @EventMapping
    public void handleMessageEvent(MessageEvent event) {
        if (!webhookIdempotencyService.markAsProcessed(event.webhookEventId())) {
            return;
        }
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
            final String userMessage = textMessageContent.text().trim();
            log.info("📩 ได้รับข้อความจากลูกค้า [{}]: {}", lineUserId, userMessage);

            // ⚡ โยนงานหนัก (flow + AI) เข้า worker pool — ไม่บล็อก thread ของ webhook
            // คืน 200 ให้ LINE ทันที กัน thread ของ Tomcat ถูกถือค้างระหว่างรอ AI/DB
            messageProcessingExecutor.submit(() ->
                    handleCustomerTextMessage(lineUserId, userMessage, replyToken));
        }

        // ==========================================
        // 3. กรณีลูกค้าส่ง "รูปภาพ"
        // ==========================================
        else if (event.message() instanceof ImageMessageContent) {
            lastImageReceivedTime.put(lineUserId, Instant.now());
            log.info("📸 ได้รับรูปภาพจาก userId: {} -> หน่วงเวลา 3 วิ", lineUserId);

            imageBatchExecutor.submit(() -> {
                try {
                    Thread.sleep(IMAGE_BATCH_WAIT_MS);

                    Instant lastTime = lastImageReceivedTime.get(lineUserId);
                    if (lastTime != null && Instant.now().minusMillis(IMAGE_BATCH_THRESHOLD_MS).isAfter(lastTime)) {
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
                    Thread.currentThread().interrupt();
                    log.error("Error during image wait", e);
                }
            });
        }
        // ==========================================
        // 4. 🛡️ กรณีลูกค้าส่ง "สติกเกอร์"
        // ==========================================
        else if (event.message() instanceof StickerMessageContent) {
            log.info("🛡️ ลูกค้าส่งสติกเกอร์ -> แจ้งเตือนให้พิมพ์ข้อความ");
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    replyToken,
                    List.of(new TextMessage("น้องทันใจยังไม่เข้าใจความหมายของสติกเกอร์ครับ 😅 รบกวนลูกค้าพิมพ์เป็นข้อความแจ้งน้องอีกครั้งนะครับ 🙏")),
                    false
            ));
        }
        // ==========================================
        // 5. 🛡️ กรณีลูกค้าส่ง "เสียง (Voice Message)" หรือ "วิดีโอ"
        // ==========================================
        else if (event.message() instanceof AudioMessageContent || event.message() instanceof VideoMessageContent) {
            log.info("🛡️ ลูกค้าส่งเสียงหรือวิดีโอ -> แจ้งเตือนให้พิมพ์ข้อความ/รูป");
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    replyToken,
                    List.of(new TextMessage("ขออภัยด้วยครับ 😅 น้องทันใจยังไม่สามารถฟังเสียงหรือดูวิดีโอได้ รบกวนลูกค้าพิมพ์ข้อความ หรือส่งเป็นรูปภาพนิ่งน้า 🙏")),
                    false
            ));
        }
        // ==========================================
        // 6. 🛡️ กรณีลูกค้าส่ง "Location (แผนที่)"
        // ==========================================
        else if (event.message() instanceof LocationMessageContent locationMessage) {
            log.info("🛡️ ลูกค้าส่ง Location");

            // ⚡ โยนเข้า worker pool เช่นเดียวกับข้อความ (ภายในเรียก flow + AI)
            messageProcessingExecutor.submit(() ->
                    handleLocationMessage(lineUserId, locationMessage, replyToken));
        }
    }

    // ==========================================
    // ⚙️ ประมวลผลข้อความลูกค้า — รันบน messageProcessingExecutor (ไม่ใช่ thread ของ webhook)
    // ==========================================
    private void handleCustomerTextMessage(String lineUserId, String userMessage, String replyToken) {
        conversationLockService.runLocked(lineUserId,
                () -> handleCustomerTextMessageLocked(lineUserId, userMessage, replyToken));
    }

    private void handleCustomerTextMessageLocked(String lineUserId, String userMessage, String replyToken) {
        String msg = userMessage.toLowerCase();

        // 🚨 Panic Mode: ตรวจจับคำว่า แอดมิน / คุยกับคน
        // เช็คด้วย regex ก่อน เพื่อไม่ต้องโหลด user_states โดยไม่จำเป็นในทางปกติ
        // (ทางปกติจะโหลด state ครั้งเดียวภายใน ChatFlowManager → ตัด SELECT ซ้ำ)
        //
        // ⚠️ ยกเว้นข้อความจากปุ่มริชเมนู (เช่น "คุยกับแอดมิน") — ปุ่มนั้นทางร้านต้องการให้พาเข้า
        //    flow ปกติ (ตอบด้วยการ์ดต้อนรับให้เลือกบริการ) ไม่ใช่เรียกแอดมินทันที
        //    ถ้าไม่ยกเว้น จะโดน regex คำว่า "แอดมิน" ดักไปก่อนตั้งแต่ตรงนี้
        boolean isPanic = !ChatFlowManager.isWelcomeMenuTrigger(userMessage)
                && msg.matches(".*(แอดมิน|ติดต่อแอดมิน|คุยกับคน|อ่านดีๆ|บอท|บอกไปแล้ว|ไม่รู้เรื่อง|อะไรเนี่ย).*");

        if (isPanic) {
            // โหลด/สร้าง State เฉพาะตอน panic เท่านั้น
            UserState userState = userStateService.loadOrCreate(lineUserId);
            // เก็บบริการ/สเต็ปที่ลูกค้าคุยค้างไว้ก่อน เพราะเดี๋ยว state จะถูกทับเป็น ADMIN_MODE
            String pendingService = userState.getServiceName();
            String pendingStep = userState.getCurrentState();

            rememberStepStateForResume(userState); // จำสเต็ปเดิมไว้ให้ปุ่ม "คืนบอท" พากลับมาต่อได้
            userState.setCurrentState("ADMIN_MODE");
            userState.setLastUserMessage(userMessage); // บันทึกความจำ
            clearFollowUpReminder(userState);
            userStateRepository.save(userState);

            String customerName = getCustomerName(lineUserId);

            // แจ้งเตือนแอดมินในกลุ่มเดียวกับเคสของ flow และบอกด้วยว่าลูกค้าค้างอยู่บริการไหน สเต็ปไหน
            // แอดมินจะได้รู้บริบททันทีว่าต้องคุยต่อเรื่องผ่อนบอลลูน หรือจำนำ iCloud
            lineMessageService.sendEmergencyCard(
                    PANIC_ADMIN_GROUP_ID,
                    pendingService != null && !pendingService.isBlank() ? pendingService : "ติดต่อทั่วไป",
                    toServiceCode(pendingService),
                    customerName,
                    lineUserId,
                    "ลูกค้าต้องการคุยกับแอดมินนุด🫶🏻🥳💵"
                            + (pendingStep != null ? "\nค้างอยู่ที่: " + pendingStep : "")
                            + "\nข้อความ: " + userMessage
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
    // ⚙️ ประมวลผลพิกัด (Location) — รันบน messageProcessingExecutor เช่นกัน
    // ==========================================
    private void handleLocationMessage(String lineUserId, LocationMessageContent locationMessage, String replyToken) {
        // แอบดึงชื่อจังหวัดหรือที่อยู่จาก Location ออกมาให้ AI ประมวลผลต่อได้เลย! (ถือว่าลูกค้าพิมพ์ข้อความ)
        String addressInfo = locationMessage.address() != null ? locationMessage.address() : "";
        String titleInfo = locationMessage.title() != null ? locationMessage.title() : "";
        String combinedLocationText = titleInfo + " " + addressInfo;

        // โยนข้อมูลที่อยู่ เข้าไปใน Flow เหมือนลูกค้าพิมพ์ตัวอักษรปกติ
        String replyText = chatFlowManager.handleTextMessage(lineUserId, combinedLocationText);
        if (replyText != null && !replyText.isEmpty()) {
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    replyToken, List.of(new TextMessage(replyText)), false
            ));
        } else {
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    replyToken, List.of(new TextMessage("น้องทันใจได้รับพิกัดแล้วครับ 📍 รบกวนลูกค้าพิมพ์ยืนยัน 'ชื่อจังหวัด' ให้น้องทันใจอีกครั้งเพื่อความชัวร์นะครับ 😊")), false
            ));
        }
    }

    // ==========================================
    // 🎯 รับ Event แอดมินกดปุ่ม (Postback)
    // ==========================================
    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        if (!webhookIdempotencyService.markAsProcessed(event.webhookEventId())) {
            return;
        }
        String postbackData = event.postback().data();
        log.info("🎯 แอดมินกดปุ่ม Postback Data: {}", postbackData);

        try {
            Map<String, String> dataMap = parsePostbackData(postbackData);
            String action = dataMap.get("action");
            String targetUserId = dataMap.get("userId");

            if (targetUserId == null || action == null) return;

            String customerName = getCustomerName(targetUserId);
            PostbackResult result = conversationLockService.callLocked(targetUserId,
                    () -> processPostbackAction(action, targetUserId, customerName));

            // ตอบแอดมินในกลุ่ม
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    event.replyToken(), List.of(new TextMessage(result.adminReplyMessage())), false
            ));

            // เด้งแจ้งลูกค้า
            if (result.messageToCustomer() != null) {
                messagingApiClient.pushMessage(null, new PushMessageRequest(
                        targetUserId, List.of(new TextMessage(result.messageToCustomer())), false, (List<String>) null
                ));
            }

        } catch (Exception e) {
            log.error("❌ Error processing postback: ", e);
        }
    }

    PostbackResult processPostbackAction(String action, String targetUserId, String customerName) {
        UserState state = userStateService.loadOrCreate(targetUserId);
        return switch (action) {
            case "approve", "approve_doc", "approve_credit" ->
                    approveCase(state, targetUserId, customerName);
            case "reject", "reject_credit" -> rejectCase(state, customerName);
            case "take_case" -> takeCase(state, customerName);
            case "resume_bot" -> resumeBot(state, customerName);
            default -> new PostbackResult("", null);
        };
    }

    private PostbackResult approveCase(UserState state, String targetUserId, String customerName) {
        if ("ADMIN_PHOTO_CHECK".equals(state.getCurrentState())) {
            state.setCurrentState("STEP_9_APPROVED_PHOTO");
            userStateRepository.save(state);
            String customerMessage = chatFlowManager.handleTextMessage(targetUserId, "continue");
            return new PostbackResult(
                    "✅ ตรวจสภาพผ่าน! (ลูกค้า: " + customerName + ")\nบอทกำลังขอรูปตั้งค่าต่อครับ",
                    customerMessage);
        }

        state.setCurrentState("STEP_5_PRICING");
        userStateRepository.save(state);
        String pricingMessage = chatFlowManager.handleTextMessage(targetUserId, "continue");
        String customerMessage = "🎉 ยินดีด้วยครับ! ข้อมูลของคุณได้รับการอนุมัติเรียบร้อยแล้ว แอดมินจะรีบดำเนินการขั้นตอนต่อไปให้นะครับ";
        if (pricingMessage != null) {
            customerMessage += "\n\n" + pricingMessage;
        }
        return new PostbackResult(
                "✅ อนุมัติเคสผ่านเรียบร้อย! (ลูกค้า: " + customerName + ")\nระบบส่งราคาให้ลูกค้าแล้วครับ",
                customerMessage);
    }

    private PostbackResult rejectCase(UserState state, String customerName) {
        boolean isPhotoCheck = "ADMIN_PHOTO_CHECK".equals(state.getCurrentState());
        String adminMessage = isPhotoCheck
                ? "❌ ปฏิเสธสภาพเครื่องเรียบร้อยครับ (ลูกค้า: " + customerName + ")\n(บอทแจ้งลูกค้าแล้ว)"
                : "❌ เคสนี้ถูกปฏิเสธเรียบร้อยครับ (ลูกค้า: " + customerName + ")";
        String customerMessage = isPhotoCheck
                ? "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบรูปภาพ สภาพเครื่องยังไม่ตรงตามเงื่อนไขการรับเครื่องของทางร้านครับ หากมีข้อสงสัยสอบถามแอดมินเพิ่มเติมได้เลยครับ"
                : "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบข้อมูล ยังไม่ผ่านเกณฑ์การพิจารณาครับ หากมีข้อสงสัยสอบถามแอดมินได้เลยครับ";
        state.setCurrentState("REJECTED");
        clearFollowUpReminder(state);
        userStateRepository.save(state);
        return new PostbackResult(adminMessage, customerMessage);
    }

    private PostbackResult takeCase(UserState state, String customerName) {
        rememberStepStateForResume(state);
        state.setCurrentState("ADMIN_MODE");
        clearFollowUpReminder(state);
        userStateRepository.save(state);
        return new PostbackResult(
                "💬 รับเรื่องแล้ว! (ปิดบอทชั่วคราว) คุยกับลูกค้า (" + customerName + ") ต่อในแชท 1-on-1 ได้เลยครับ",
                "แอดมินมารับเรื่องแล้วครับ! พิมพ์สอบถามได้เลยครับ 👇");
    }

    private PostbackResult resumeBot(UserState state, String customerName) {
        String previousState = state.getPreviousState();
        state.setCurrentState(previousState != null ? previousState : "STEP_1_INFO");
        state.setPreviousState(null);
        if (state.getServiceName() == null || state.getServiceName().isBlank()) {
            state.setServiceName(BALLOON_SERVICE);
        }
        userStateRepository.save(state);
        return new PostbackResult(
                "▶️ เปิดบอทให้ดูแลลูกค้า (" + customerName + ") ต่อแล้วครับ",
                "น้องทันใจกลับมาดูแลต่อแล้วครับ ✨ รบกวนลูกค้าพิมพ์คำตอบของขั้นตอนเมื่อสักครู่นี้ ให้น้องทันใจบันทึกลงระบบอีกครั้งนะครับ 👇");
    }

    record PostbackResult(String adminReplyMessage, String messageToCustomer) {
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
        return lineProfileService.getDisplayName(userId);
    }

    private void clearFollowUpReminder(UserState userState) {
        userState.setFollowUpReminderStartedAt(null);
        userState.setFollowUpReminderSent(false);
    }

    /**
     * แปลงชื่อบริการภาษาไทย → โค้ดสั้นที่การ์ดแอดมินใช้ (ไปอยู่ใน postback data)
     */
    private String toServiceCode(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return "general";
        if (serviceName.contains("บอลลูน")) return "balloon";
        if (serviceName.contains("iCloud") || serviceName.contains("จำนำ")) return "icloud";
        return "general";
    }

    /**
     * จำสเต็ปปัจจุบันไว้ให้ปุ่ม "คืนบอท" (resume_bot) พาลูกค้ากลับมาต่อได้ถูกที่
     * เก็บเฉพาะ state ที่เป็นสเต็ปของ flow จริงๆ — ถ้าเผลอเก็บ ADMIN_MODE/REJECTED ทับ
     * ตอนกดคืนบอทจะวนกลับเข้าโหมดแอดมินอีกรอบ = บอทเงียบถาวร
     */
    private void rememberStepStateForResume(UserState userState) {
        String currentState = userState.getCurrentState();
        if (currentState != null && currentState.startsWith("STEP_")) {
            userState.setPreviousState(currentState);
        }
    }
}
