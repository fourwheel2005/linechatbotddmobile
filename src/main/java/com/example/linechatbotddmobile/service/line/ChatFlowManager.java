package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
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

    public String handleTextMessage(String lineUserId, String userMessage) {

        UserState userState = userStateRepository.findByLineUserId(lineUserId).orElseGet(() -> {
            UserState newUser = new UserState();
            newUser.setLineUserId(lineUserId);
            return newUser;
        });

        String msg = userMessage.trim().toLowerCase();

        if ("ADMIN_MODE".equals(userState.getCurrentState())) return null;

        // ถ้าลูกค้าพิมพ์คำสั่งเป๊ะๆ เข้า Flow รีบอลลูน
        if (msg.equals("รีบอลลูน") || msg.equals("ผ่อนบอลลูน")) {
            userState.setCurrentState("STEP_1_INFO");
            userState.setServiceName("รีบอลลูน");
            userStateRepository.save(userState);
        }

        String currentService = userState.getServiceName();

        // 🌟 ถ้ากำลังอยู่ใน Flow บริการ (เช่น รีบอลลูน) ให้ Flow จัดการ
        if (currentService != null && !currentService.isEmpty()) {
            for (ServiceFlowHandler handler : flowHandlers) {
                if (handler.supports(currentService)) {
                    return handler.processMessage(userState, userMessage);
                }
            }
        }

        // ========================================================
        // 🌟 ถ้าไม่ได้อยู่ในบริการไหนเลย (ถามทั่วไป) -> โยนให้ AiChatService ตอบ
        // ========================================================
        log.info("🤖 ลูกค้าถามทั่วไป โยนให้ AI Chat Service ตอบ");
        return aiChatService.generateResponse(lineUserId, userMessage);
    }
}