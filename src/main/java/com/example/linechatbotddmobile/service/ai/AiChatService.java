package com.example.linechatbotddmobile.service.ai;


import com.example.linechatbotddmobile.entity.ChatHistory;
import com.example.linechatbotddmobile.repository.ChatHistoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AiChatService {

    private final ChatClient chatClient;
    private final ChatHistoryRepository chatHistoryRepository;

    @Value("classpath:prompt/base-system-prompt.st")
    private Resource systemPromptResource;

    private String systemPromptCache;

    public AiChatService(ChatClient.Builder chatClientBuilder, ChatHistoryRepository chatHistoryRepository) {
        this.chatClient = chatClientBuilder.build();
        this.chatHistoryRepository = chatHistoryRepository;
    }

    @PostConstruct
    public void loadSystemPrompt() {
        try (InputStream is = systemPromptResource.getInputStream()) {
            this.systemPromptCache = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            log.info("✅ Loaded base-system-prompt.st into memory ({} chars)", systemPromptCache.length());
        } catch (Exception e) {
            log.error("❌ ไม่สามารถโหลด base-system-prompt.st ได้: ", e);
            this.systemPromptCache = "";
        }
    }

    public String generateResponse(String lineUserId, String userMessage) {
        try {
            // 1. บันทึกคำถามของลูกค้าลง Database
            saveChatHistory(lineUserId, "USER", userMessage);

            // 2. ใช้ System Prompt จาก cache (โหลดครั้งเดียวตอน startup)
            String systemPrompt = systemPromptCache;
            List<Message> messageList = new ArrayList<>();
            messageList.add(new SystemMessage(systemPrompt));

            // 🌟 3. ดึงประวัติเก่ามาประกอบ (เทคนิค Sliding Window: เอาแค่ 10 ข้อความล่าสุด)
            // หมายเหตุ: คุณต้องไปสร้าง findTop10ByLineUserIdOrderByCreatedAtDesc ใน Repository นะครับ
            List<ChatHistory> historyList = chatHistoryRepository.findTop10ByLineUserIdOrderByCreatedAtDesc(lineUserId);

            // ต้องสลับลำดับจากเก่าไปใหม่ เพื่อให้ AI อ่านรู้เรื่อง
            for (int i = historyList.size() - 1; i >= 0; i--) {
                ChatHistory h = historyList.get(i);
                if ("USER".equals(h.getRole())) {
                    messageList.add(new UserMessage(h.getContent()));
                } else {
                    messageList.add(new AssistantMessage(h.getContent()));
                }
            }

            // 4. ให้ AI คิดคำตอบ
            String aiResponse = chatClient.prompt()
                    .messages(messageList)
                    .call()
                    .content();

            // 5. บันทึกคำตอบของ AI ลง Database
            saveChatHistory(lineUserId, "ASSISTANT", aiResponse);

            return aiResponse;

        } catch (Exception e) {
            log.error("❌ AI Chat Service Error: ", e);
            return "ขออภัยครับ ตอนนี้แอดมินบอทกำลังมึนงง รบกวนพิมพ์ใหม่อีกครั้งนะครับ 😅";
        }
    }

    private void saveChatHistory(String userId, String role, String content) {
        ChatHistory history = new ChatHistory();
        history.setLineUserId(userId);
        history.setRole(role);
        history.setContent(content);
        chatHistoryRepository.save(history);
    }
}