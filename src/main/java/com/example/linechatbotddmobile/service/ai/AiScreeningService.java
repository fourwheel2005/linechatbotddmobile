package com.example.linechatbotddmobile.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AiScreeningService {

    // กำหนดรูปแบบผลลัพธ์ที่มีได้แค่ 3 หน้าท่ีนี้
    public enum ScreeningAnswer { YES, NO, UNCLEAR }

    private final ChatClient chatClient;

    @Value("classpath:prompt/screening-prompt.st")
    private Resource screeningPromptResource;

    public AiScreeningService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * ตีความคำตอบของลูกค้า
     */
    public ScreeningAnswer interpret(String currentMessage, String lastMessage) {
        try {
            String context = (lastMessage != null && !lastMessage.trim().isEmpty()) ? lastMessage : "ไม่มี";
            String combinedMessage = "ข้อความก่อนหน้า: " + context + " | ข้อความล่าสุด: " + currentMessage;

            log.info("🔍 [Screening] กำลังตีความ: {}", combinedMessage);

            String rawResponse = chatClient.prompt()
                    .system(sys -> sys.text(screeningPromptResource))
                    .user(u -> u.text(combinedMessage))
                    .call()
                    .content();

            if (rawResponse == null) {
                log.warn("⚠️ [Screening] AI ไม่ตอบอะไรกลับมา → ถือว่า UNCLEAR");
                return ScreeningAnswer.UNCLEAR;
            }

            // ทำความสะอาดข้อความ เผื่อ AI เผลอส่ง ``` กลับมาด้วย
            String cleaned = rawResponse.replace("```json", "")
                    .replace("```", "")
                    .trim()
                    .toUpperCase();

            // เช็คผลลัพธ์
            if (cleaned.contains("UNCLEAR")) {
                log.info("✅ [Screening] ผลลัพธ์ → UNCLEAR");
                return ScreeningAnswer.UNCLEAR;
            }
            if (cleaned.contains("YES")) {
                log.info("✅ [Screening] ผลลัพธ์ → YES");
                return ScreeningAnswer.YES;
            }
            if (cleaned.contains("NO")) {
                log.info("✅ [Screening] ผลลัพธ์ → NO");
                return ScreeningAnswer.NO;
            }

            log.warn("⚠️ [Screening] AI ตอบนอกเหนือจากคำสั่ง: {} → ถือว่า UNCLEAR", cleaned);
            return ScreeningAnswer.UNCLEAR;

        } catch (Exception e) {
            log.error("❌ [Screening] Error: ", e);
            return ScreeningAnswer.UNCLEAR;
        }
    }
}