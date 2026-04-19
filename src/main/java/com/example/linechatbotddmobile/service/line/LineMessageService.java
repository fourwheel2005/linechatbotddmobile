package com.example.linechatbotddmobile.service.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.FlexMessage;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.messaging.model.FlexContainer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineMessageService {

    private final MessagingApiClient messagingApiClient;
    private final ObjectMapper objectMapper;

    // อ้างอิงไฟล์ JSON จากโฟลเดอร์ resources/flex/ (ตามภาพที่คุณแนบมา)
    @Value("classpath:flex/admin_approval_card.json")
    private Resource adminApprovalCardTemplate;

    @Value("classpath:flex/emergency_card.json")
    private Resource emergencyCardTemplate;

    // ตัวแปรสำหรับเก็บ Cache
    private String adminCardJsonCache;
    private String emergencyCardJsonCache;

    @PostConstruct
    public void initTemplates() {
        try {
            adminCardJsonCache = StreamUtils.copyToString(adminApprovalCardTemplate.getInputStream(), StandardCharsets.UTF_8);
            emergencyCardJsonCache = StreamUtils.copyToString(emergencyCardTemplate.getInputStream(), StandardCharsets.UTF_8);
            log.info("✅ โหลด Flex Message Templates เข้าหน่วยความจำสำเร็จ (DDMobile)");
        } catch (Exception e) {
            log.error("❌ ไม่สามารถโหลด Flex Message Templates ได้ (ตรวจเช็คชื่อไฟล์ในโฟลเดอร์ resources/flex/): ", e);
        }
    }


    public void sendAdminApprovalCard(String toGroupId, String serviceName, String serviceType, String customerDetails, String userId, String extraInfo) {
        try {
            // 👇 แก้ไขตรง .replace ให้ตรงกับไฟล์ JSON ของคุณเป๊ะๆ
            String finalJson = adminCardJsonCache
                    .replace("{{SERVICE_NAME}}", escapeJson(serviceName))
                    .replace("{{SERVICE_EN}}", escapeJson(serviceType))         // เปลี่ยนเป็น {{SERVICE_EN}}
                    .replace("{{CUSTOMER_NAME}}", escapeJson(customerDetails))  // เปลี่ยนเป็น {{CUSTOMER_NAME}}
                    .replace("{{USER_ID}}", escapeJson(userId))
                    .replace("{{DEVICE_MODEL}}", escapeJson(extraInfo));        // เปลี่ยนเป็น {{DEVICE_MODEL}}

            executePushMessage(toGroupId, "📝 มีเคสรออนุมัติ/ประเมินราคาใหม่", finalJson);
        } catch (Exception e) {
            log.error("❌ Error sendAdminApprovalCard: ", e);
            sendTextMessage(toGroupId, "📝 รออนุมัติ\nลูกค้า: " + customerDetails + "\n" + extraInfo);
        }
    }

    /**
     * 🚨 ส่งการ์ดฉุกเฉิน (Emergency Flex)
     */
    public void sendEmergencyCard(String toGroupId, String serviceName, String customerName, String reason) {
        try {
            // แทนที่ตัวแปร
            String finalJson = emergencyCardJsonCache
                    .replace("{{SERVICE_NAME}}", escapeJson(serviceName))
                    .replace("{{CUSTOMER_NAME}}", escapeJson(customerName))
                    .replace("{{REASON}}", escapeJson(reason));

            executePushMessage(toGroupId, "🚨 ลูกค้าต้องการความช่วยเหลือ!", finalJson);
        } catch (Exception e) {
            log.error("❌ Error sendEmergencyCard: ", e);
            sendTextMessage(toGroupId, "🚨 แจ้งเตือนแอดมิน!\nลูกค้า: " + customerName + "\nสาเหตุ: " + reason);
        }
    }

    // =========================================================
    // Core Messaging Methods
    // =========================================================

    public void sendTextMessage(String toUserId, String text) {
        try {
            messagingApiClient.pushMessage(
                    UUID.randomUUID(),
                    new PushMessageRequest(toUserId, List.of(new TextMessage(text)), false, null)
            );
        } catch (Exception e) {
            log.error("❌ ล้มเหลวในการส่งข้อความหา {}: ", toUserId, e);
        }
    }

    public void replyText(String replyToken, String text) {
        try {
            List<Message> messages = List.of(new TextMessage(text));
            messagingApiClient.replyMessage(new ReplyMessageRequest(replyToken, messages, false));
        } catch (Exception e) {
            log.error("❌ ไม่สามารถตอบกลับข้อความได้: ", e);
        }
    }

    public void sendImage(String to, String imageUrl) {
        try {
            ImageMessage imageMessage = new ImageMessage(URI.create(imageUrl), URI.create(imageUrl));
            PushMessageRequest pushMessageRequest = new PushMessageRequest(to, List.of(imageMessage), false, null);
            messagingApiClient.pushMessage(UUID.randomUUID(), pushMessageRequest).get();
        } catch (Exception e) {
            log.error("❌ เกิดข้อผิดพลาดในการส่งรูปภาพถึง: {}", to, e);
        }
    }

    // =========================================================
    // Private Helper Methods
    // =========================================================

    private void executePushMessage(String groupId, String altText, String jsonPayload) throws Exception {
        FlexContainer flexContainer = objectMapper.readValue(jsonPayload, FlexContainer.class);
        FlexMessage flexMessage = new FlexMessage(altText, flexContainer);

        PushMessageRequest request = new PushMessageRequest(
                groupId,
                List.of(flexMessage),
                false,
                null
        );

        messagingApiClient.pushMessage(UUID.randomUUID(), request);
    }

    /**
     * 🛡️ ระบบความปลอดภัย (JSON Anti-Injection)
     */
    private String escapeJson(String input) {
        if (input == null) return "-";
        return input.replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }
}