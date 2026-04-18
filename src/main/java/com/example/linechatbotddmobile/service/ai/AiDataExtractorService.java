package com.example.linechatbotddmobile.service.ai;

import com.example.linechatbotddmobile.dto.ExtractedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AiDataExtractorService {

    private final ChatClient chatClient;

    // ดึงไฟล์ Prompt ที่เราเตรียมไว้มาใช้งาน
    @Value("classpath:prompt/extractor-prompt.st")
    private Resource extractorPromptTemplate;

    public AiDataExtractorService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * สกัดข้อมูล Model และ Age จากข้อความลูกค้า
     */
    public ExtractedData extractInfo(String currentMessage, String lastMessage) {
        try {
            // 🧠 ดึงความจำเดิมมาเชื่อมกับข้อความใหม่
            String context = (lastMessage != null && !lastMessage.trim().isEmpty()) ? lastMessage : "ไม่มี";
            String combinedMessage = "ข้อความก่อนหน้า: " + context + " | ข้อความล่าสุด: " + currentMessage;

            log.info("🔍 [Extractor] ให้ AI สกัดข้อมูลจาก: {}", combinedMessage);

            // 🌟 ให้ Spring AI จัดการส่ง Prompt และแปลงผลลัพธ์ (JSON) กลับมาเป็น Object ให้เลย
            ExtractedData result = chatClient.prompt()
                    .system(sys -> sys.text(extractorPromptTemplate))
                    .user(u -> u.text(combinedMessage))
                    .call()
                    .entity(ExtractedData.class);

            log.info("✅ [Extractor] สกัดสำเร็จ: Model={}, Age={}", result.deviceModel(), result.age());

            // 🛡️ ดักจับ Null เพื่อไม่ให้ Flow พัง
            return new ExtractedData(
                    result.deviceModel() != null && !result.deviceModel().isEmpty() ? result.deviceModel() : "unknown",
                    result.age() != null ? result.age() : 0
            );

        } catch (Exception e) {
            log.error("❌ [Extractor] ทำงานล้มเหลว: ", e);
            // คืนค่า Default กลับไปให้ Flow ถามลูกค้าใหม่
            return new ExtractedData("unknown", 0);
        }
    }
}