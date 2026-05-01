package com.example.linechatbotddmobile.service.ai;

import com.example.linechatbotddmobile.dto.ScreeningType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;



@Slf4j
@Service
public class AiScreeningService {

    public enum ScreeningAnswer { YES, NO, UNCLEAR }
    public enum ScreeningType {
        REPAIR,
        FACE_ID,
        INSTALLMENT
    }

    private final ChatClient chatClient;

    @Value("classpath:prompt/screening-prompt.st")
    private Resource screeningPromptResource;

    public AiScreeningService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * ตีความคำตอบของลูกค้า
     */
    public ScreeningAnswer interpret(ScreeningType type, String currentMessage) {
        try {
            String combinedMessage = """
                    ประเภทคำถาม: %s
                    ข้อความล่าสุดของลูกค้า: %s
                    """.formatted(type.name(), currentMessage);

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

            String cleaned = rawResponse
                    .replace("```json", "")
                    .replace("```", "")
                    .replace("\"", "")
                    .trim()
                    .toUpperCase();

            cleaned = cleaned.split("\\R")[0].trim();

            return switch (cleaned) {
                case "YES" -> ScreeningAnswer.YES;
                case "NO" -> ScreeningAnswer.NO;
                case "UNCLEAR" -> ScreeningAnswer.UNCLEAR;
                default -> {
                    log.warn("⚠️ [Screening] AI ตอบนอกเหนือจากคำสั่ง: raw='{}', cleaned='{}' → ถือว่า UNCLEAR", rawResponse, cleaned);
                    yield ScreeningAnswer.UNCLEAR;
                }
            };

        } catch (Exception e) {
            log.error("❌ [Screening] Error: ", e);
            return ScreeningAnswer.UNCLEAR;
        }
    }

    private ScreeningAnswer interpretByRules(ScreeningType type, String raw) {
        String msg = normalize(raw);

        return switch (type) {
            case REPAIR -> interpretRepairByRules(msg);
            case FACE_ID -> interpretFaceIdByRules(msg);
            case INSTALLMENT -> interpretInstallmentByRules(msg);
        };
    }

    private ScreeningAnswer interpretFaceIdByRules(String msg) {
        // เช็กคำลบก่อน เพราะ "ไม่ได้" มีคำว่า "ได้"
        if (containsAny(msg,
                "ไม่ได้", "สแกนไม่ได้", "แสกนไม่ได้",
                "สแกนไม่ติด", "แสกนไม่ติด",
                "เสีย", "พัง", "บอด",
                "เฟสไอดีเสีย", "เฟสไอดีบอด",
                "faceidเสีย", "faceidบอด")) {
            return ScreeningAnswer.NO;
        }

        if (containsAny(msg,
                "ปกติ", "ปรกติ", "ปกติดี",
                "ใช้ได้", "ใช้งานได้",
                "สแกนได้", "แสกนได้",
                "ได้ปกติ")) {
            return ScreeningAnswer.YES;
        }

        return ScreeningAnswer.UNCLEAR;
    }

    private ScreeningAnswer interpretRepairByRules(String msg) {
        if (containsAny(msg,
                "ซ่อมบอร์ด", "เปลี่ยนบอร์ด",
                "แกะซ่อม", "เคยแกะ", "เคยซ่อม",
                "เปลี่ยนฝาหลัง", "ฝาหลังแตก")) {
            return ScreeningAnswer.YES;
        }

        if (containsAny(msg,
                "ไม่เคย", "ไม่เคยแกะ", "ไม่เคยซ่อม",
                "ไม่เคยซ่อมเลย", "เดิมๆ", "เดิมเดิม",
                "ของแท้", "สภาพใหม่", "เพิ่งซื้อ",
                "เปลี่ยนแบต", "เปลี่ยนจอ")) {
            return ScreeningAnswer.NO;
        }

        return ScreeningAnswer.UNCLEAR;
    }

    private ScreeningAnswer interpretInstallmentByRules(String msg) {
        // ต้องเช็ก "ไม่ติด" ก่อนคำว่า "ติด"
        if (containsAny(msg,
                "ไม่ติด", "ไม่ติดผ่อน", "ไม่ติดไอคราว",
                "ไม่ติดicloud", "ไม่ติดไอคลาวด์",
                "ซื้อสด", "ผ่อนหมดแล้ว",
                "เครื่องเปล่า", "ไม่ค้าง", "ไม่มีค้าง")) {
            return ScreeningAnswer.NO;
        }

        if (containsAny(msg,
                "ติดผ่อน", "ติดไอคราว", "ติดicloud",
                "ติดไอคลาวด์", "ติดคลาวด์",
                "ติดล็อค", "ติดล๊อค", "ค้างผ่อน")) {
            return ScreeningAnswer.YES;
        }

        // อย่าใส่คำว่า "ติด" เดี่ยว ๆ ถ้ายังไม่ normalize ดีพอ
        return ScreeningAnswer.UNCLEAR;
    }

    private String normalize(String raw) {
        if (raw == null) return "";

        return raw
                .toLowerCase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace("face id", "faceid")
                .replace("เฟส id", "เฟสไอดี")
                .replace("เฟสไอดี", "เฟสไอดี")
                .trim();
    }

    private boolean containsAny(String msg, String... keywords) {
        for (String keyword : keywords) {
            if (msg.contains(keyword.toLowerCase().replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }
}