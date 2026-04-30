package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.ChatHistoryRepository;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiChatService; // 🌟 นำเข้า AiChatService
import com.example.linechatbotddmobile.service.flow.ServiceFlowHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFlowManager {

    private final UserStateRepository userStateRepository;
    private final List<ServiceFlowHandler> flowHandlers;
    private final AiChatService aiChatService; // 🌟 ฉีด AiChatService เข้ามา
    private final ChatHistoryRepository chatHistoryRepository;
    private final LineMessageService lineMessageService;


    @Transactional
    public String handleTextMessage(String lineUserId, String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) return null;

        UserState userState = userStateRepository.findByLineUserId(lineUserId).orElseGet(() -> {
            UserState newUser = new UserState();
            newUser.setLineUserId(lineUserId);
            return newUser;
        });

        String msgLower = userMessage.trim().toLowerCase();

        // ✅ เริ่มใหม่ — ล้างข้อมูลทั้งหมด
        if (msgLower.equals("เริ่มใหม่") || msgLower.equals("ยกเลิก")) {
            chatHistoryRepository.deleteByLineUserId(lineUserId);
            userStateRepository.delete(userState);
            return "ล้างข้อมูลเรียบร้อยแล้วครับ 🔄 ลูกค้าสามารถพิมพ์ 'สนใจผ่อน' เพื่อเริ่มต้นใหม่ได้เลยครับ 😊";
        }

        if ("ADMIN_MODE".equals(userState.getCurrentState()) || "ADMIN_PHOTO_CHECK".equals(userState.getCurrentState())) {
            return null; // บอทเงียบเวลาแอดมินทำงาน
        }

        boolean isInterest = msgLower.matches(".*(ผ่อน|ดาวน์|ราคา|สนใจ|บอลลูน|รับเครื่อง|เริ่ม).*");
        boolean isReject = msgLower.matches(".*(ไม่สน|ไม่ผ่อน|แพง|ยกเลิก).*");

        if (isInterest && !isReject && userState.getCurrentState() == null) {
            userState.setCurrentState("STEP_1_INFO");
            userState.setServiceName("สนใจผ่อนบอลลูน");
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

            userState.setCurrentState("ADMIN_MODE");
            userStateRepository.save(userState);

            // แจ้งเตือนเข้ากลุ่มแอดมิน
            // (อย่าลืม Inject LineMessageService เข้ามาใน ChatFlowManager ด้วยนะครับ)
            lineMessageService.sendEmergencyCard(
                    "C76744781eae27ba2499edb000665e436", // ADMIN_GROUP_ID
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