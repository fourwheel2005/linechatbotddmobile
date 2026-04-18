package com.example.linechatbotddmobile.service.line; // ปรับ package ให้ตรงกับของคุณ

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineMessageService {

    private final MessagingApiClient messagingApiClient;
    // หากมีระบบอ่านไฟล์ JSON Flex จาก resources ก็เรียกใช้ตัวช่วยอ่านไฟล์ได้ที่นี่
    // private final FlexMessageBuilder flexMessageBuilder;

    /**
     * ส่งข้อความตัวอักษรปกติ (Push Message)
     */
    public void sendTextMessage(String toUserId, String text) {
        try {
            messagingApiClient.pushMessage(
                    null,
                    new PushMessageRequest(
                            toUserId,
                            List.of(new com.linecorp.bot.messaging.model.TextMessage(text)),
                            false,
                            (List<String>) null
                    )
            );
            log.info("📤 ส่งข้อความหา {} สำเร็จ", toUserId);
        } catch (Exception e) {
            log.error("❌ ล้มเหลวในการส่งข้อความหา {}: ", toUserId, e);
        }
    }

    /**
     * ส่งรูปภาพ (Push Image)
     * ใช้สำหรับส่งตัวอย่างหน้าจอตั้งค่าให้ลูกค้าดู
     */
    public void sendImage(String toUserId, String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            ImageMessage imageMessage = new ImageMessage(uri, uri); // Original URL, Preview URL

            messagingApiClient.pushMessage(
                    null,
                    new PushMessageRequest(
                            toUserId,
                            List.of(imageMessage),
                            false,
                            (List<String>) null
                    )
            );
            log.info("📸 ส่งรูปภาพหา {} สำเร็จ", toUserId);
        } catch (Exception e) {
            log.error("❌ ล้มเหลวในการส่งรูปภาพหา {}: ", toUserId, e);
        }
    }

    /**
     * 🚨 ส่งการ์ดฉุกเฉิน (Emergency Flex) เข้ากลุ่มแอดมิน
     * เมื่อลูกค้าหงุดหงิด หรือพิมพ์คำว่า "แอดมิน"
     */
    public void sendEmergencyCard(String toGroupId, String serviceName, String customerName, String reason) {
        log.info("🚨 กำลังส่ง Emergency Card ไปที่กลุ่ม: {} (ลูกค้า: {})", toGroupId, customerName);

        // -------------------------------------------------------------
        // TODO: นำเข้า JSON String ของ Flex Message ที่คุณออกแบบไว้ที่นี่
        // หรือดึงจากไฟล์ resources/flex/emergency_card.json
        // -------------------------------------------------------------

        // เพื่อให้โปรเจกต์รันผ่านไปก่อน ผมจะสร้าง Flex Message แบบง่าย (Bubble) ผ่าน Code ให้ดูครับ
        // (แนะนำให้เปลี่ยนไปใช้การอ่านไฟล์ JSON ในอนาคตเพื่อความสวยงามและแก้ง่าย)

        try {
            String altText = "🚨 ลูกค้าต้องการความช่วยเหลือ!";

            // สร้างข้อความแจ้งเตือนแบบ Text ไปก่อน (หากยังไม่มี Flex JSON)
            // หากคุณมี Flex Builder แล้ว สามารถแปลง JSON เป็น FlexContainer แล้วใส่แทน TextMessage ได้
            String alertMsg = String.format("🚨 **แจ้งเตือนแอดมิน!** 🚨\n" +
                            "บริการ: %s\n" +
                            "ลูกค้า: %s\n" +
                            "สาเหตุ: %s\n\n" +
                            "👉 แอดมินกรุณาเข้าไปดูแลลูกค้าในแชท 1-on-1 ด่วนครับ!",
                    serviceName, customerName, reason);

            sendTextMessage(toGroupId, alertMsg);

            // ตัวอย่างการส่ง Flex ถ้ามี JSON (ต้องใช้ไลบรารีแปลง JSON เป็น FlexContainer ของ LINE SDK)
            // FlexContainer flexContainer = flexMessageBuilder.buildEmergencyFlex(customerName, reason);
            // FlexMessage flexMessage = new FlexMessage(altText, flexContainer);
            // messagingApiClient.pushMessage(null, new PushMessageRequest(toGroupId, List.of(flexMessage), false, null));

        } catch (Exception e) {
            log.error("❌ ล้มเหลวในการส่ง Emergency Card", e);
        }
    }

    /**
     * 📝 ส่งการ์ดขออนุมัติราคา (Admin Approval Flex) เข้ากลุ่มแอดมิน
     * เมื่อลูกค้ายื่นเรื่องรีบอลลูนเสร็จสมบูรณ์
     */
    public void sendAdminApprovalCard(String toGroupId, String serviceName, String serviceType, String customerDetails, String customerId, String extraInfo) {
        log.info("📝 กำลังส่ง Approval Card ไปที่กลุ่ม: {} (ลูกค้า: {})", toGroupId, customerDetails);

        // -------------------------------------------------------------
        // TODO: นำเข้า JSON String ของ Flex Message ที่คุณออกแบบไว้ที่นี่
        // -------------------------------------------------------------

        try {
            // สร้างข้อความแจ้งเตือนแบบ Text ชั่วคราว (จนกว่าคุณจะประกอบ JSON Flex เสร็จ)
            String approvalMsg = String.format("📝 **รออนุมัติ / ประเมินราคา** 📝\n" +
                            "บริการ: %s\n" +
                            "ลูกค้า: %s\n" +
                            "รายละเอียด: %s\n\n" +
                            "👉 แอดมินกรุณาตรวจสอบสภาพเครื่อง และกดส่งราคาในระบบครับ",
                    serviceName, customerDetails, extraInfo);

            sendTextMessage(toGroupId, approvalMsg);

            // ถ้ามี Flex JSON ก็ใช้ Postback Action แนบไปกับปุ่ม
            // ตัวอย่าง Data สำหรับปุ่มอนุมัติ: "action=approve&service=" + serviceType + "&userId=" + customerId

        } catch (Exception e) {
            log.error("❌ ล้มเหลวในการส่ง Approval Card", e);
        }
    }
}