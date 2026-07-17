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

    // ID ของกลุ่มแอดมิน (เปลี่ยนเป็นของคุณ)
    private final String MAIN_ADMIN_GROUP_ID = "Ced29a5fec5e581b47ffa61d9845e71bf";

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
        String msg = userMessage.toLowerCase();

        // 🚨 Panic Mode: ตรวจจับคำว่า แอดมิน / คุยกับคน
        // เช็คด้วย regex ก่อน เพื่อไม่ต้องโหลด user_states โดยไม่จำเป็นในทางปกติ
        // (ทางปกติจะโหลด state ครั้งเดียวภายใน ChatFlowManager → ตัด SELECT ซ้ำ)
        boolean isPanic = msg.matches(".*(แอดมิน|ติดต่อแอดมิน|คุยกับคน|อ่านดีๆ|บอท|บอกไปแล้ว|ไม่รู้เรื่อง|อะไรเนี่ย).*");

        if (isPanic) {
            // โหลด/สร้าง State เฉพาะตอน panic เท่านั้น
            UserState userState = userStateRepository.findByLineUserId(lineUserId)
                    .orElseGet(() -> {
                        UserState newUser = new UserState();
                        newUser.setLineUserId(lineUserId);
                        return newUser;
                    });
            userState.setCurrentState("ADMIN_MODE");
            userState.setLastUserMessage(userMessage); // บันทึกความจำ
            clearFollowUpReminder(userState);
            userStateRepository.save(userState);

            String customerName = getCustomerName(lineUserId);

            // แจ้งเตือนแอดมินในกลุ่ม
            lineMessageService.sendEmergencyCard(
                    MAIN_ADMIN_GROUP_ID,
                    "ติดต่อทั่วไป",
                    "general",
                    customerName,
                    lineUserId,
                    "ลูกค้าต้องการคุยกับแอดมินนุด🫶🏻🥳💵"
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
            String serviceName = dataMap.get("service");
            String targetUserId = dataMap.get("userId");

            if (targetUserId == null || action == null) return;

            // 🌟 1. เพิ่มบรรทัดนี้: ดึงชื่อลูกค้าเตรียมไว้
            String customerName = getCustomerName(targetUserId);

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
                        // 👇 2. แทรกชื่อลูกค้าตรงนี้
                        adminReplyMessage = "✅ ตรวจสภาพผ่าน! (ลูกค้า: " + customerName + ")\nบอทกำลังขอรูปตั้งค่าต่อครับ";

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
                        // 👇 2. แทรกชื่อลูกค้าตรงนี้
                        adminReplyMessage = "✅ อนุมัติเคสผ่านเรียบร้อย! (ลูกค้า: " + customerName + ")\nระบบส่งราคาให้ลูกค้าแล้วครับ";
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
                        // 👇 2. แทรกชื่อลูกค้าตรงนี้
                        adminReplyMessage = "❌ ปฏิเสธสภาพเครื่องเรียบร้อยครับ (ลูกค้า: " + customerName + ")\n(บอทแจ้งลูกค้าแล้ว)";
                        messageToCustomer = "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบรูปภาพ สภาพเครื่องยังไม่ตรงตามเงื่อนไขการรับเครื่องของทางร้านครับ หากมีข้อสงสัยสอบถามแอดมินเพิ่มเติมได้เลยครับ";
                    } else {
                        // 2. ปฏิเสธเคสขั้นสุดท้าย
                        // 👇 2. แทรกชื่อลูกค้าตรงนี้
                        adminReplyMessage = "❌ เคสนี้ถูกปฏิเสธเรียบร้อยครับ (ลูกค้า: " + customerName + ")";
                        messageToCustomer = "ต้องขออภัยด้วยนะครับ 🙏 จากการตรวจสอบข้อมูล ยังไม่ผ่านเกณฑ์การพิจารณาครับ หากมีข้อสงสัยสอบถามแอดมินได้เลยครับ";
                    }
                    state.setCurrentState("REJECTED");
                    clearFollowUpReminder(state);
                    userStateRepository.save(state);
                    break;

                case "take_case":
                    // 👇 2. แทรกชื่อลูกค้าตรงนี้
                    adminReplyMessage = "💬 รับเรื่องแล้ว! (ปิดบอทชั่วคราว) คุยกับลูกค้า (" + customerName + ") ต่อในแชท 1-on-1 ได้เลยครับ";
                    messageToCustomer = "แอดมินมารับเรื่องแล้วครับ! พิมพ์สอบถามได้เลยครับ 👇";
                    state.setCurrentState("ADMIN_MODE");
                    clearFollowUpReminder(state);
                    userStateRepository.save(state);
                    break;

                case "resume_bot":
                    // 👇 2. แทรกชื่อลูกค้าตรงนี้
                    adminReplyMessage = "▶️ เปิดบอทให้ดูแลลูกค้า (" + customerName + ") ต่อแล้วครับ";

                    // 👇 ดึงความจำเดิมกลับมา
                    String prevState = state.getPreviousState();
                    if (prevState != null) {
                        state.setCurrentState(prevState); // กลับไปสเต็ปที่ค้างอยู่
                        state.setPreviousState(null); // ล้างความจำทิ้ง
                    } else {
                        state.setCurrentState("STEP_1_INFO"); // กันเหนียวกรณีไม่มีความจำ
                    }
                    userStateRepository.save(state);

                    // 👇 แจ้งให้ลูกค้าทราบว่าบอทกลับมาแล้ว และให้ลูกค้าพิมพ์ข้อมูลให้บอทเก็บลง Database
                    messageToCustomer = "น้องทันใจกลับมาดูแลต่อแล้วครับ ✨ รบกวนลูกค้าพิมพ์คำตอบของขั้นตอนเมื่อสักครู่นี้ ให้น้องทันใจบันทึกลงระบบอีกครั้งนะครับ 👇";
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
        return lineProfileService.getDisplayName(userId);
    }

    private void clearFollowUpReminder(UserState userState) {
        userState.setFollowUpReminderStartedAt(null);
        userState.setFollowUpReminderSent(false);
    }
}
