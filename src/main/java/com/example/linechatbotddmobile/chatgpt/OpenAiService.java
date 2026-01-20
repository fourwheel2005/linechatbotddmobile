package com.example.linechatbotddmobile.chatgpt;

import com.example.linechatbotddmobile.product.Product;
import com.example.linechatbotddmobile.product.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAiService {

    private final OpenAIProperties openAIProperties;
    private final WebClient webClient;
    private final ProductService productService;

    private static final String BASE_SYSTEM_PROMPT = """
            คุณคือผู้ช่วย AI ของร้าน DDmobile ร้านขายมือถือและอุปกรณ์เสริมต่างๆ
            
            ข้อมูลร้าน:
            - ที่อยู่: 123 ถนนสุขุมวิท กรุงเทพฯ
            - เวลาเปิด-ปิด: จันทร์-ศุกร์ 10:00-20:00, เสาร์-อาทิตย์ 11:00-19:00
            - โทร: 02-xxx-xxxx
            - Line ID: @ddmobile
            
            บริการ:
            - ผ่อน 0% นาน 10 เดือน (บัตรเครดิต)
            - รับซื้อเครื่องเก่า
            - ประกันเครื่อง 1 ปี
            - ฟรีฟิล์มกระจกเมื่อซื้อเครื่อง
            
            กฎการตอบ:
            1. ตอบด้วยภาษาสุภาพ เป็นกันเอง แต่เป็นมืออาชีพ
            2. ใช้ข้อมูลสินค้าที่ให้ไปในการตอบเท่านั้น
            3. ถ้าไม่แน่ใจแนะนำให้โทรสอบถาม
            4. บอกโปรโมชั่นที่เกี่ยวข้องเสมอ
            5. ตอบกระชับ ไม่ยาวเกินไป
            """;

    public OpenAiService(OpenAIProperties openAIProperties,
                         WebClient.Builder webClientBuilder,
                         ProductService productService) {
        this.openAIProperties = openAIProperties;
        this.productService = productService;
        this.webClient = webClientBuilder
                .baseUrl(openAIProperties.getApi().getUrl())
                .defaultHeader("Authorization", "Bearer " + openAIProperties.getApi().getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String getChatGptResponse(String userMessage) {
        // ตรวจจับคีย์เวิร์ดและค้นหาสินค้า
        List<Product> relevantProducts = detectAndSearchProducts(userMessage);

        // สร้าง system prompt พร้อมข้อมูลสินค้า
        String systemPrompt = buildSystemPrompt(relevantProducts);

        String model = openAIProperties.getModel();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_tokens", 1000,
                "temperature", 0.7
        );

        try {
            log.info("Sending request to OpenAI with message: {}", userMessage);

            Map response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("choices")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");

                if (!choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");

                    log.info("Received response from OpenAI");
                    return content != null ? content.trim() : "ไม่สามารถรับคำตอบได้ครับ";
                }
            }

            log.warn("No choices found in OpenAI response");
            return "ไม่สามารถประมวลผลคำตอบได้ครับ";

        } catch (WebClientResponseException e) {
            log.error("OpenAI API error", e);
            return "ขออภัยครับ ระบบขัดข้องชั่วคราว โปรดลองใหม่อีกครั้ง";
        } catch (Exception e) {
            log.error("Unexpected error", e);
            return "ขออภัยครับ เกิดข้อผิดพลาดที่ไม่คาดคิด";
        }
    }

    // ตรวจจับคีย์เวิร์ดและค้นหาสินค้า
    private List<Product> detectAndSearchProducts(String userMessage) {
        String lowerMessage = userMessage.toLowerCase();

        // คีย์เวิร์ดสินค้า
        String[] keywords = {
                "iphone", "samsung", "xiaomi", "airpods", "galaxy",
                "ไอโฟน", "ซัมซุง", "เสียวหมี่", "แอร์พอด", "หูฟัง",
                "เคส", "ฟิล์ม", "สายชาร์จ", "หัวชาร์จ"
        };

        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword)) {
                return productService.searchProducts(keyword);
            }
        }

        return List.of();
    }

    // สร้าง system prompt พร้อมข้อมูลสินค้า
    private String buildSystemPrompt(List<Product> products) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);

        if (!products.isEmpty()) {
            prompt.append("\n").append(productService.formatProductInfo(products));
        } else {
            // ถ้าไม่มีสินค้าที่เจาะจง ให้ใส่ข้อมูลทั้งหมด
            prompt.append("\n").append(productService.generateProductKnowledge());
        }

        return prompt.toString();
    }
}