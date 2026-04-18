package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.ChatHistoryRepository;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiChatService; // 🌟 นำเข้า AiChatService
import com.example.linechatbotddmobile.service.flow.ServiceFlowHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFlowManager {

    private final UserStateRepository userStateRepository;
    private final List<ServiceFlowHandler> flowHandlers;
    private final AiChatService aiChatService; // 🌟 ฉีด AiChatService เข้ามา
    private final ChatHistoryRepository chatHistoryRepository;

    public String handleTextMessage(String lineUserId, String userMessage) {

        UserState userState = userStateRepository.findByLineUserId(lineUserId).orElseGet(() -> {
            UserState newUser = new UserState();
            newUser.setLineUserId(lineUserId);
            return newUser;
        });

        String msg = userMessage.trim();
        String msgLower = msg.toLowerCase();

        // ✅ เริ่มใหม่ — ล้างข้อมูลทั้งหมดของลูกค้าคนนี้
        if (msgLower.equals("เริ่มใหม่")) {
            chatHistoryRepository.deleteByLineUserId(lineUserId);
            userStateRepository.delete(userState);
            log.info("🗑️ ล้างข้อมูลลูกค้า {} เรียบร้อยแล้ว", lineUserId);
            return "ล้างข้อมูลเรียบร้อยแล้วครับ 🔄 ลูกค้าสามารถเริ่มต้นใหม่ได้เลยครับ 😊";
        }

        if ("ADMIN_MODE".equals(userState.getCurrentState())) return null;

        if (msgLower.equals("รีบอลลูน") || msgLower.equals("ผ่อนบอลลูน")) {
            userState.setCurrentState("STEP_1_INFO");
            userState.setServiceName("รีบอลลูน");
            userStateRepository.save(userState);
        }

        String currentService = userState.getServiceName();

        if (currentService != null && !currentService.isEmpty()) {
            for (ServiceFlowHandler handler : flowHandlers) {
                if (handler.supports(currentService)) {
                    return handler.processMessage(userState, userMessage);
                }
            }
        }

        log.info("🤖 ลูกค้าถามทั่วไป โยนให้ AI Chat Service ตอบ");
        return aiChatService.generateResponse(lineUserId, userMessage);
    }
}