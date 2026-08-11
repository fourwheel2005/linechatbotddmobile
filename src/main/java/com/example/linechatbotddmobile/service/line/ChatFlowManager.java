package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.config.AdminGroup;
import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.ChatHistoryRepository;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiChatService; // 🌟 นำเข้า AiChatService
import com.example.linechatbotddmobile.service.flow.ServiceFlowHandler;
import com.example.linechatbotddmobile.util.IphoneModelPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFlowManager {

    private static final String ADMIN_GROUP_ID = AdminGroup.MAIN_GROUP_ID;
    private static final String BALLOON_SERVICE = "ผ่อนบอลลูน";
    private static final String ICLOUD_SERVICE = "จำนำ iCloud";

    /**
     * ข้อความที่ปุ่มริชเมนูส่งเข้ามา แล้วต้องตอบด้วย "การ์ดต้อนรับ" ให้ลูกค้ากดเลือกบริการเอง
     *
     * ริชเมนู "ทันใจทันใช้" มี 6 ช่อง (A-F) แต่ A-E เป็นลิงก์ (Facebook / รูป / แผนที่)
     * ซึ่งไม่วิ่งเข้าบอทเลย — มีแค่ช่อง F "คุยกับแอดมิน" ที่เป็น action แบบข้อความ
     * และตั้งค่าให้ส่งคำว่า "ทันใจทันใช้" เข้ามา บอทจึงดักที่ข้อความนี้
     *
     * ⚠️ ทางร้านต้องการให้ปุ่ม F พาลูกค้าเข้า flow ปกติ (เลือกบริการเอง) ไม่ใช่เรียกแอดมินทันที
     *    ถ้าวันหลังเปลี่ยนข้อความที่ปุ่มส่ง หรือเพิ่มปุ่มใหม่ ให้มาเพิ่มในลิสต์นี้ที่เดียว
     *    — LineWebhookController จะยกเว้นข้อความในลิสต์นี้จาก panic mode ให้อัตโนมัติ
     *    (กันกรณีข้อความปุ่มมีคำว่า "แอดมิน" แล้วโดน regex ดักไปก่อนถึงบอท)
     */
    private static final Set<String> WELCOME_MENU_TRIGGERS = Set.of(
            "ทันใจทันใช้"
    );

    /** ข้อความนี้มาจากปุ่มริชเมนูที่ต้องตอบด้วยการ์ดต้อนรับหรือไม่ */
    public static boolean isWelcomeMenuTrigger(String userMessage) {
        return userMessage != null && WELCOME_MENU_TRIGGERS.contains(userMessage.trim());
    }

    private final UserStateRepository userStateRepository;
    private final List<ServiceFlowHandler> flowHandlers;
    private final AiChatService aiChatService; // 🌟 ฉีด AiChatService เข้ามา
    private final ChatHistoryRepository chatHistoryRepository;
    private final LineMessageService lineMessageService;
    private final LineProfileService lineProfileService;


    /**
     * ไม่ใส่ @Transactional ที่ระดับเมธอด — ภายในมีการเรียก OpenAI/LINE API (blocking I/O หลายวินาที)
     * ถ้าครอบทั้งเมธอดด้วย transaction เดียว DB connection จะถูกถือค้างตลอดช่วง I/O
     * ทำให้ connection pool หมดเร็วเมื่อมี traffic พร้อมกัน (อาการบอทค้าง).
     * แต่ละ repository call (find/save/delete) มี transaction สั้นของตัวเองอยู่แล้ว
     * จึงยืม-คืน connection เฉพาะตอนแตะ DB จริงเท่านั้น
     */
    public String handleTextMessage(String lineUserId, String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) return null;

        UserState userState = userStateRepository.findByLineUserId(lineUserId).orElseGet(() -> {
            UserState newUser = new UserState();
            newUser.setLineUserId(lineUserId);
            return newUser;
        });

        String trimmedMessage = userMessage.trim();
        String msgLower = trimmedMessage.toLowerCase();

        // ✅ เริ่มใหม่ — ล้างข้อมูลทั้งหมด
        if (msgLower.equals("เริ่มใหม่") || msgLower.equals("ยกเลิก")) {
            chatHistoryRepository.deleteByLineUserId(lineUserId);
            userStateRepository.delete(userState);
            return "ล้างข้อมูลเรียบร้อยแล้วครับ 🔄 ลูกค้าสามารถพิมพ์ 'สนใจผ่อน' เพื่อเริ่มต้นใหม่ได้เลยครับ 😊";
        }

        // 📱 ปุ่ม "ผ่อนบอลลูน" จากการ์ดต้อนรับ → เริ่มต้น flow ใหม่เสมอ
        // เช็คด้วยข้อความตรงตัวที่ปุ่มส่งมา ("สนใจผ่อนบอลลูน") เท่านั้น เพื่อไม่ให้ไปรีเซ็ต
        // ลูกค้าที่พิมพ์คำว่า "ผ่อน" กลาง flow โดยไม่ตั้งใจ
        // ต้องอยู่ก่อนเช็ค ADMIN_MODE เพื่อกันอาการกดปุ่มแล้วเงียบ (ต้องพิมพ์ "เริ่มใหม่" เอง)
        if (trimmedMessage.equals("สนใจผ่อนบอลลูน")) {
            return startBalloonFlow(lineUserId, userState, trimmedMessage);
        }

        if ("ADMIN_MODE".equals(userState.getCurrentState()) || "ADMIN_PHOTO_CHECK".equals(userState.getCurrentState())) {
            return null; // บอทเงียบเวลาแอดมินทำงาน
        }

        // 📋 ปุ่มริชเมนู (ช่อง F "คุยกับแอดมิน" → ส่งข้อความ "ทันใจทันใช้") → ยิงการ์ดต้อนรับที่มีปุ่ม 2 บริการ
        // ใช้การ์ด (Flex) แทนข้อความให้ลูกค้าพิมพ์เอง เพราะปุ่มส่งข้อความตรงตัวเสมอ
        // ("สนใจผ่อนบอลลูน" / "สนใจจำนำ iCloud") — ตัดปัญหาลูกค้าพิมพ์เพี้ยนแล้วไม่เข้า flow
        // วางหลังเช็ค ADMIN_MODE เพื่อให้ตอนแอดมินทำงานบอทยังเงียบอยู่ (ไม่เด้งทับ)
        // และวางก่อน dispatch เข้า flow เพื่อไม่ยุ่งกับ state ที่ค้าง — ลูกค้ากดกลาง flow
        // ก็ได้เมนูโดย flow เดิมไม่ถูกรีเซ็ต
        if (isWelcomeMenuTrigger(trimmedMessage)) {
            lineMessageService.sendWelcomeCard(lineUserId);
            return null; // การ์ดถูก push ไปแล้ว ไม่ต้องตอบข้อความซ้ำ
        }

        // 🧭 ลูกค้า "กำลังคุยค้างอยู่กลาง flow" อยู่หรือเปล่า
        // ใช้ตัดสินว่าข้อความนี้ควรถูกดักด้วยคีย์เวิร์ดกลางๆ (รุ่นต่ำกว่า 13 / จำนำ / สนใจผ่อน)
        // หรือควรปล่อยให้ flow ที่ค้างอยู่เป็นคนตอบ
        boolean isFlowInProgress = isFlowInProgress(userState);

        // ⚠️ ดักเฉพาะตอนที่ยังไม่ได้อยู่กลาง flow — ถ้าอยู่กลาง flow ให้ BalloonFlowService
        // เป็นคนเช็คเองตาม step (STEP_2_CAPACITY) จะได้ไม่ไปกินคำตอบของ step อื่น
        if (!isFlowInProgress && IphoneModelPolicy.isUnsupportedBelowIphone13Message(msgLower)) {
            return IphoneModelPolicy.UNSUPPORTED_BELOW_IPHONE_13_MESSAGE;
        }

        // 🔒 สนใจจำนำ iCloud → ส่งต่อให้แอดมินรับช่วงต่อทันที (ไม่มี flow อัตโนมัติ)
        // ต้องเช็คก่อน isInterest เพราะข้อความมีคำว่า "สนใจ" ซึ่งจะไปเข้าเงื่อนไขผ่อนบอลลูนโดยไม่ตั้งใจ
        // • กดจากปุ่มการ์ด = ตั้งใจชัดเจน → รับเสมอ แม้กำลังคุยค้างอยู่ใน flow บอลลูน
        // • พิมพ์คำว่า "จำนำ" เอง → รับเฉพาะตอนไม่ได้อยู่กลาง flow เพราะ STEP_8 ถามว่า
        //   "ติดผ่อน/ติด iCloud ร้านอื่นไหม" ลูกค้าตอบ "ไม่ได้จำนำที่ไหนครับ" = คำตอบของ flow
        boolean isIcloudButtonTap = msgLower.equals("สนใจจำนำ icloud");
        if (isIcloudButtonTap || (isIcloudPawnRequest(msgLower) && !isFlowInProgress)) {
            userState.setPreviousState(userState.getCurrentState());
            userState.setCurrentState("ADMIN_MODE");
            userState.setServiceName(ICLOUD_SERVICE);
            userState.setLastUserMessage(userMessage);
            userStateRepository.save(userState);

            lineMessageService.sendEmergencyCard(
                    ADMIN_GROUP_ID,
                    ICLOUD_SERVICE,
                    "icloud",
                    lineProfileService.getDisplayName(lineUserId),
                    lineUserId,
                    "ลูกค้าสนใจบริการจำนำ iCloud 🔒 รบกวนแอดมินรับช่วงต่อครับ"
            );

            // ข้อความกระชับ + รู้เวลาทำการเหมือนฝั่งบอลลูน (getWaitMessage)
            // ในเวลาทำการ → บอกรอสักครู่, นอกเวลา → บอกชัดว่าพรุ่งนี้เช้าแอดมินมาดูแลคิวแรก
            String icloudWaitMessage = "รับทราบครับ 🙏 บริการ **จำนำ iCloud** 🔒 เดี๋ยวแอดมินเข้ามาดูแลต่อให้เลยนะครับ";
            if (isBusinessHours()) {
                icloudWaitMessage += " รบกวนรอสักครู่นะครับ ⏳";
            } else {
                icloudWaitMessage += "\n\nแต่ตอนนี้นอกเวลาทำการแล้ว (เปิด 08:30 - 19:00 น.) พรุ่งนี้เช้าเวลา 08:30 น. แอดมินจะรีบเข้ามาดูแลให้เป็นคิวแรกเลยนะครับ 🙏💤";
            }
            return icloudWaitMessage;
        }

        boolean isInterest = msgLower.matches(".*(ผ่อน|ดาวน์|ราคา|สนใจ|บอลลูน|รับเครื่อง|เริ่ม).*");
        boolean isReject = msgLower.matches(".*(ไม่สน|ไม่ผ่อน|แพง|ยกเลิก).*");

        // 🎈 ลูกค้าแสดงความสนใจ และยังไม่ได้คุยค้างอยู่กลาง flow → เริ่ม flow ผ่อนบอลลูนให้เลย
        // ⚠️ เดิมเงื่อนไขคือ currentState == null เท่านั้น ทำให้ "ลูกค้าเก่าที่มี state ค้าง"
        //    (เช่น REJECTED, state ที่ serviceName หาย, หรือ serviceName เป็น "จำนำ iCloud"
        //    ซึ่งไม่มี handler รองรับ) กลับเข้า flow ไม่ได้อีกเลย — ทุกข้อความจะร่วงไปให้ AI ตอบ
        //    = อาการ "บอทตอบไม่เข้า flow" (ตอบเองเรื่อยๆ ไม่ถามความจุ/จังหวัด/อายุ)
        if (isInterest && !isReject && !isFlowInProgress) {
            return startBalloonFlow(lineUserId, userState, trimmedMessage);
        }

        // 🩹 กู้ state กำพร้า: currentState ค้างอยู่ที่ STEP_* แต่ serviceName ว่าง/ไม่มี handler รองรับ
        //    เกิดได้จากเส้นทางที่ตั้ง state โดยไม่ตั้ง serviceName (panic mode, [CALL_ADMIN], ปุ่มคืนบอท)
        //    ถ้าไม่กู้ ลูกค้าจะหา handler ไม่เจอ → ตกไป AI ทุกข้อความ = หลุด flow ถาวร
        if (isStepState(userState.getCurrentState()) && !hasHandlerFor(userState.getServiceName())) {
            log.warn("🩹 พบ state กำพร้า userId={} state={} serviceName={} → ผูกกลับเข้า flow {}",
                    lineUserId, userState.getCurrentState(), userState.getServiceName(), BALLOON_SERVICE);
            userState.setServiceName(BALLOON_SERVICE);
            userStateRepository.save(userState);
        }

        String currentService = userState.getServiceName();

        // 🏃‍♂️ ส่งเข้า Flow
        if (currentService != null && !currentService.isEmpty()) {
            for (ServiceFlowHandler handler : flowHandlers) {
                if (handler.supports(currentService)) {
                    return handler.processMessage(userState, userMessage);
                }
            }
        }

        // 🤖 โยนให้ AI Chat Service ตอบคำถามทั่วไป
        log.info("🤖 ลูกค้าถามทั่วไป โยนให้ AI Chat Service ตอบ");
        String aiResponse = aiChatService.generateResponse(lineUserId, userMessage);

        // 🚨 Smart Handover (Option B): ถ้า AI บอกว่าตอบไม่ได้ ให้ตัดเข้าแอดมินโหมด
        if (aiResponse != null && aiResponse.contains("[CALL_ADMIN]")) {
            // ลบแท็ก [CALL_ADMIN] ออกก่อนส่งให้ลูกค้าเห็น
            aiResponse = aiResponse.replace("[CALL_ADMIN]", "").trim();

            // จำสเต็ปเดิมไว้ให้ปุ่ม "คืนบอท" พากลับมาต่อได้ถูกที่ (ถ้าค้างอยู่กลาง flow)
            if (isStepState(userState.getCurrentState())) {
                userState.setPreviousState(userState.getCurrentState());
            }
            userState.setCurrentState("ADMIN_MODE");
            userStateRepository.save(userState);

            // แจ้งเตือนเข้ากลุ่มแอดมิน
            // (อย่าลืม Inject LineMessageService เข้ามาใน ChatFlowManager ด้วยนะครับ)
            lineMessageService.sendEmergencyCard(
                    ADMIN_GROUP_ID,
                    "คำถามทั่วไป",
                    "general",
                    "ลูกค้า", // ถ้าดึงชื่อได้ให้ใส่ชื่อ
                    lineUserId,
                    "AI ไม่สามารถตอบได้ จึงส่งต่อให้แอดมินครับ"
            );

            // 🌟 เพิ่มการเช็คเวลาตรงนี้ (ถ้านอกเวลาทำการ ให้พ่วงข้อความแจ้งเตือนต่อท้ายคำตอบของ AI)
            if (!isBusinessHours()) {
                aiResponse += "\n\nแต่ตอนนี้นอกเวลาทำการแล้ว (เปิด 08:30 - 19:00 น.) พรุ่งนี้เช้าแอดมินจะรีบเข้ามาดูแลให้นะครับ 🙏💤";
            }
        }

        return aiResponse;
    }

    // ==========================================
    // 🛠️ Helper Method: เริ่ม flow ผ่อนบอลลูนใหม่ตั้งแต่ต้น
    // (ใช้ร่วมกันทั้งปุ่มการ์ดต้อนรับ และกรณีลูกค้าพิมพ์แสดงความสนใจเอง)
    // ==========================================
    private String startBalloonFlow(String lineUserId, UserState userState, String userMessage) {
        chatHistoryRepository.deleteByLineUserId(lineUserId); // ล้างความจำ AI กันตอบทับ flow
        userState.setCurrentState("STEP_1_INFO");
        userState.setServiceName(BALLOON_SERVICE);
        userState.setPreviousState(null);
        userState.setRetryCount(0);
        userState.setLastUserMessage(userMessage);
        userState.setFollowUpReminderStartedAt(null);
        userState.setFollowUpReminderSent(false);
        userStateRepository.save(userState);

        for (ServiceFlowHandler handler : flowHandlers) {
            if (handler.supports(BALLOON_SERVICE)) {
                return handler.processMessage(userState, userMessage);
            }
        }

        log.error("❌ ไม่พบ ServiceFlowHandler ของบริการ {} — ลูกค้าจะไม่ได้รับคำตอบ", BALLOON_SERVICE);
        return null;
    }

    // ==========================================
    // 🛠️ Helper Method: ตรวจว่าลูกค้าคุยค้างอยู่กลาง flow จริงหรือไม่
    // นับเฉพาะกรณีที่ serviceName ยังมี handler รองรับ และเดินหน้าไปแล้วจริงๆ
    // (STEP_1_INFO คือจุดตั้งต้น ยังไม่ถือว่าเริ่มคุย → รีเซ็ตทับได้ไม่เสียหาย)
    // ==========================================
    private boolean isFlowInProgress(UserState userState) {
        String currentState = userState.getCurrentState();
        if (!isStepState(currentState) || "STEP_1_INFO".equals(currentState)) return false;
        return hasHandlerFor(userState.getServiceName());
    }

    private boolean isStepState(String currentState) {
        return currentState != null && currentState.startsWith("STEP_");
    }

    private boolean hasHandlerFor(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return false;
        for (ServiceFlowHandler handler : flowHandlers) {
            if (handler.supports(serviceName)) return true;
        }
        return false;
    }

    // ==========================================
    // 🛠️ Helper Method: ลูกค้าขอใช้บริการ "จำนำ iCloud" จริงหรือไม่
    // ตัดคำปฏิเสธออก ("ไม่ได้จำนำ", "ไม่เคยจำนำ") ซึ่งเป็นคำตอบของคำถามใน flow
    // ==========================================
    private boolean isIcloudPawnRequest(String msgLower) {
        if (!msgLower.contains("จำนำ")) return false;
        return !msgLower.matches(".*ไม่.{0,6}จำนำ.*");
    }

    // ==========================================
    // 🛠️ Helper Method: ระบบเช็คเวลาทำการของร้าน
    // ==========================================
    private boolean isBusinessHours() {
        // เวลาปัจจุบันในประเทศไทย
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Bangkok"));

        // 🔴 ตั้งเวลาเปิดร้าน (08:30 น.)
        java.time.LocalTime openTime = java.time.LocalTime.of(8, 30);

        // 🔴 ตั้งเวลาปิดร้าน (19:00 น.)
        java.time.LocalTime closeTime = java.time.LocalTime.of(19, 0);

        // คืนค่า true ถ้าร้านเปิดอยู่
        return !now.isBefore(openTime) && !now.isAfter(closeTime);
    }
}
