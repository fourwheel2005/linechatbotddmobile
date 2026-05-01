package com.example.linechatbotddmobile.service.flow;

import com.example.linechatbotddmobile.dto.ExtractedData;
import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiDataExtractorService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService.ScreeningAnswer;
import com.example.linechatbotddmobile.service.line.LineMessageService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalloonFlowService implements ServiceFlowHandler {

    private final UserStateRepository userStateRepository;
    private final LineMessageService lineMessageService;
    private final MessagingApiClient messagingApiClient;
    private final AiDataExtractorService aiDataExtractorService;
    private final AiScreeningService aiScreeningService;

    private final String ADMIN_GROUP_ID = "C76744781eae27ba2499edb000665e436";

    public record BalloonPrice(int buyPrice, int m6, int m8, int m10, int m12) {}

    @Override
    public boolean supports(String serviceName) {
        return "ผ่อนบอลลูน".equals(serviceName);
    }

    @Override
    public String getServiceName() { return "ผ่อนบอลลูน"; }

    @Override
    public String processMessage(UserState userState, String userMessage) {
        String state = userState.getCurrentState() != null ? userState.getCurrentState() : "STEP_1_INFO";
        String msg = userMessage.trim();
        String userId = userState.getLineUserId();
        String lastMessage = userState.getLastUserMessage();

        boolean isPanic = msg.matches(".*(แอดมิน|คุยกับคน|อ่านดีๆ|บอกไปแล้ว|บอท|ไม่รู้เรื่อง|อะไรเนี่ย).*");
        if (isPanic) {
            userState.setPreviousState(state);
            userState.setCurrentState("ADMIN_MODE");
            userState.setLastUserMessage(msg);
            userStateRepository.save(userState);
            lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "ลูกค้าต้องการคุยกับคน/หงุดหงิดบอท");

            if (isBusinessHours()) {
                return "รับทราบครับ น้องทันใจขออภัยในความไม่สะดวกนะครับ 🙏 เดี๋ยวแอดมินรีบเข้ามาดูแลเคสนี้ให้ทันที รบกวนรอสักครู่นะครับ ⏳";
            } else {
                return "รับทราบครับ น้องทันใจขออภัยในความไม่สะดวกนะครับ 🙏 แต่ตอนนี้อยู่นอกเวลาทำการ (เปิด 08:30 - 19:00 น.) พรุ่งนี้เช้าแอดมินตัวจริงจะรีบมาดูแลเคสนี้ให้นะครับ ⏳💤";
            }
        }

        String responseMessage = null;

        switch (state) {

            // ══════════════════════════════════════════════════════════
            case "STEP_1_INFO": // เริ่มต้น → ถามรุ่น
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("STEP_2_CAPACITY");
                responseMessage = "สวัสดีครับ 🙏😊 น้องทันใจยินดีให้บริการผ่อนบอลลูนครับ ขออนุญาตสอบถามข้อมูลเบื้องต้นนะครับ\n" +
                        "👉 ลูกค้าใช้ไอโฟน **รุ่นไหน** ครับ? (เช่น 13 Pro Max, 15 Pro, 16)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_2_CAPACITY": // รับรุ่น → ถามความจุ
                // ══════════════════════════════════════════════════════════
                ExtractedData modelData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedModel = modelData.deviceModel();

                if (extractedModel == null || "unknown".equalsIgnoreCase(extractedModel)) {
                    if (msg.matches("^(1[1-7])(?:\\s*(pro|max|plus|mini|pm|p))?.*$")) {
                        String baseModel = msg.substring(0, 2);
                        if (msg.contains("pro max") || msg.contains("pm")) extractedModel = baseModel + " Pro Max";
                        else if (msg.contains("pro") || msg.contains("p")) extractedModel = baseModel + " Pro";
                        else if (msg.contains("plus")) extractedModel = baseModel + " Plus";
                        else if (msg.contains("mini")) extractedModel = baseModel + " mini";
                        else extractedModel = baseModel;
                    }
                }

                if (extractedModel == null || "unknown".equalsIgnoreCase(extractedModel)) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "บอทสกัดรุ่นโทรศัพท์ไม่ได้", "น้องทันใจยังไม่ทราบรุ่นเลยครับ 😅 รบกวนแจ้ง 'รุ่นไอโฟน' เช่น 13 Pro Max หรือ 15 Pro อีกครั้งนะครับ 📱");
                    break;
                }

                userState.setRetryCount(0); // ผ่านแล้วล้างแต้ม
                userState.setDeviceModel(extractedModel);
                userState.setCurrentState("STEP_3_PROVINCE");
                responseMessage = "รับทราบครับ รุ่น **iPhone " + extractedModel + "** นะครับ! 📱\n" +
                        "👉 ความจุ **กี่ GB** ครับผม? (เช่น 128, 256, 512)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_3_PROVINCE": // รับความจุ → ถามจังหวัด
                // ══════════════════════════════════════════════════════════
                ExtractedData capData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedCapacity = capData.capacity();

                if (extractedCapacity == null || "unknown".equalsIgnoreCase(extractedCapacity)) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "บอทสกัดความจุไม่ได้", "รบกวนระบุความจุอีกครั้งนะครับ 🙏 เช่น 128GB, 256GB ครับ");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCapacity(extractedCapacity);
                userState.setCurrentState("STEP_4_AGE");
                responseMessage = "ความจุ **" + extractedCapacity + "** นะครับ! ✨\n" +
                        "👉 ลูกค้าอยู่ **จังหวัด** อะไรครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_4_AGE": // รับจังหวัด → ถามอายุ (อัปเดตสกัดจังหวัดแล้ว)
                // ══════════════════════════════════════════════════════════
                ExtractedData provData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedProvince = provData.province();

                if (extractedProvince == null || "unknown".equalsIgnoreCase(extractedProvince)) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "บอทสกัดชื่อจังหวัดไม่ได้", "รบกวนแจ้งจังหวัดที่ลูกค้าอยู่ให้น้องทันใจอีกครั้งนะครับ 📍 (เช่น ชลบุรี, กรุงเทพ)");
                    break;
                }

                userState.setRetryCount(0);
                userState.setProvince(extractedProvince); // บันทึกจังหวัดลงฐานข้อมูล
                userState.setCurrentState("STEP_5_REPAIR");
                responseMessage = "จังหวัด " + extractedProvince + " รับทราบครับ 📍\n" +
                        "👉 แล้วลูกค้า **อายุ** เท่าไหร่ครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_5_REPAIR": // รับอายุ → เช็คเกณฑ์ → ถามซ่อม
                // ══════════════════════════════════════════════════════════
                ExtractedData ageData = aiDataExtractorService.extractInfo(msg, lastMessage);
                Integer extractedAge = ageData.age();

                if (extractedAge == null || extractedAge == 0) {
                    try { extractedAge = Integer.parseInt(msg.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                }

                if (extractedAge == null || extractedAge == 0) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "บอทสกัดอายุไม่ได้", "รบกวนระบุตัวเลขอายุให้น้องทันใจหน่อยนะครับ (เช่น 25)");
                    break;
                }

                if (extractedAge < 18) {
                    userState.setCurrentState("ADMIN_MODE");
                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "⚠️ ลูกค้าอายุต่ำกว่าเกณฑ์: " + extractedAge + " ปี");
                    responseMessage = getWaitMessage("ขอบคุณที่แจ้งนะครับ 🙏 เกณฑ์อายุที่กำหนดอยู่ที่ **18 - 55 ปี** แอดมินได้รับเรื่องไว้แล้วครับ");
                    break;
                }

                if (extractedAge > 55) {
                    userState.setCurrentState("ADMIN_MODE");
                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "⚠️ ลูกค้าอายุเกินเกณฑ์: " + extractedAge + " ปี");
                    responseMessage = getWaitMessage("ขอบคุณที่แจ้งนะครับ 🙏 เกณฑ์อายุที่กำหนดอยู่ที่ **18 - 55 ปี** แอดมินได้รับเรื่องไว้แล้วครับ");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("STEP_6_FACEID");
                responseMessage = "อายุ " + extractedAge + " ปี รับทราบครับ 👍\n\n" +
                        "ถัดไปน้องทันใจขอเช็คประวัติเครื่องหน่อยครับ 🔍\n" +
                        "👉 เครื่องเคยแกะซ่อม หรือเปลี่ยนชิ้นส่วนใดๆ มาไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_6_FACEID": // ตรวจซ่อม → ถาม Face ID
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer repairAns = aiScreeningService.interpret(
                        AiScreeningService.ScreeningType.REPAIR,
                        msg
                );
                if (repairAns == ScreeningAnswer.YES) {
                    userState.setPreviousState(userState.getCurrentState()); // จำสเต็ปเผื่อแอดมินกดคืนร่างบอท
                    userState.setCurrentState("ADMIN_MODE"); // 🔴 เปลี่ยนจาก REJECTED เป็น ADMIN_MODE

                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "⚠️ ลูกค้าแจ้งว่าเครื่องเคยแกะซ่อม (ประเมินโดย AI)\nข้อความ: " + msg);
                    responseMessage = getWaitMessage("ขอบคุณสำหรับข้อมูลนะครับ 🙏 แอดมินได้รับเรื่องประวัติการซ่อมแล้ว");
                    break;
                }
                if (repairAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าตอบประวัติการซ่อมไม่ชัดเจน", "รบกวนตอบให้น้องทันใจชัดเจนอีกนิดครับ เช่น 'ไม่เคยแกะเลยครับ' หรือ 'เคยเปลี่ยนแบตครับ'");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("STEP_7_INSTALLMENT");
                responseMessage = "โอเคครับ 👍 แล้ว **Face ID (สแกนหน้า)** ใช้งานได้ปกติไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_7_INSTALLMENT": // ตรวจ Face ID → ถามติดผ่อน
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer faceIdAns = aiScreeningService.interpret(
                        AiScreeningService.ScreeningType.FACE_ID,
                        msg
                );
                if (faceIdAns == ScreeningAnswer.NO) {
                    userState.setPreviousState(userState.getCurrentState()); // จำสเต็ปเผื่อแอดมินกดคืนร่างบอท
                    userState.setCurrentState("ADMIN_MODE"); // 🔴 เปลี่ยนจาก REJECTED เป็น ADMIN_MODE

                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "⚠️ ลูกค้าแจ้งว่า Face ID ใช้งานไม่ได้ (ประเมินโดย AI)\nข้อความ: " + msg);
                    responseMessage = getWaitMessage("รับทราบครับ 🙏 แอดมินได้รับเรื่องการสแกนหน้าแล้ว");
                    break;
                }
                if (faceIdAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าตอบเรื่อง Face ID ไม่ชัดเจน", "รบกวนตอบให้น้องทันใจทราบชัดๆ นิดนึงครับ เช่น 'ปกติครับ' หรือ 'สแกนไม่ได้ค่ะ'");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("STEP_8_DEVICE_PHOTOS");
                responseMessage = "เยี่ยมเลยครับ 😊 แล้วเครื่องมี **ติดผ่อนกับร้านอื่น หรือติดใส่ iCloud ร้านอื่น** ไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_8_DEVICE_PHOTOS": // ตรวจติดผ่อน → ขอรูปรอบเครื่อง
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer installAns = aiScreeningService.interpret(
                        AiScreeningService.ScreeningType.INSTALLMENT,
                        msg
                );
                if (installAns == ScreeningAnswer.YES) {
                    userState.setPreviousState(userState.getCurrentState()); // จำสเต็ปเผื่อแอดมินกดคืนร่างบอท
                    userState.setCurrentState("ADMIN_MODE"); // 🔴 เปลี่ยนจาก REJECTED เป็น ADMIN_MODE

                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "⚠️ ลูกค้าแจ้งว่าเครื่องติดผ่อน/ติด iCloud (ประเมินโดย AI)\nข้อความ: " + msg);
                    responseMessage = getWaitMessage("ขอบคุณสำหรับข้อมูลครับ 🙏 แอดมินได้รับเรื่องตรวจสอบการติดผ่อนแล้ว");
                    break;
                }
                if (installAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าตอบเรื่องติดผ่อนไม่ชัดเจน", "รบกวนยืนยันใหม่อีกครั้งครับ เช่น 'ไม่ติดผ่อนครับ' หรือ 'เครื่องเปล่าครับ'");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("STEP_9_SETTINGS_PHOTO");
                responseMessage = "ผ่านการตรวจสอบเบื้องต้นเรียบร้อยครับ 🎉✅\n\n" +
                        "เพื่อให้แอดมินประเมินสภาพภายนอกได้ชัดเจน รบกวนลูกค้า:\n" +
                        "📸 **ถ่ายรูปรอบเครื่อง 4-5 รูป** (หน้า-หลัง-ข้าง)\n" +
                        "ส่งมาให้น้องทันใจดูสภาพหน่อยครับ (สามารถถ่ายผ่านกระจกเงาได้ครับ🪞)✨";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_9_SETTINGS_PHOTO": // รับรูปรอบเครื่อง → ส่ง Flex ให้แอดมินตรวจ
                // ══════════════════════════════════════════════════════════
                if (msg.equals("[รูปภาพ]")) {
                    responseMessage =  "น้องทันใจได้รับรูปแล้วครับ 📸 ทยอยส่งมาให้ครบ 4-5 รูปได้เลยนะครับ\n" +
                            "(หากส่งครบแล้ว รบกวนพิมพ์บอกแอดมินว่า **'ครบแล้ว'** ด้วยนะครับ ✨)";
                    break;
                }

                boolean isImageBatchReceived = msg.contains("ครบ") || msg.contains("ส่งแล้ว") || msg.contains("เรียบร้อย");

                if (!isImageBatchReceived) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าพิมพ์ข้อความอื่นแทนที่จะส่งรูปครบแล้ว", "น้องทันใจกำลังรอรูปรอบเครื่องอยู่นะครับ 📸\n(หากส่งรูปครบแล้ว พิมพ์บอกแอดมินว่า **'ครบแล้ว'** ได้เลยครับ ✨)");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("ADMIN_PHOTO_CHECK");

                lineMessageService.sendAdminApprovalCard(
                        ADMIN_GROUP_ID,
                        "ตรวจสภาพเครื่อง (" + getServiceName() + ")", // เติมวงเล็บให้ข้อความดูสวยขึ้น
                        "balloon",
                        getCustomerName(userId),
                        userId,
                        "รุ่น: " + userState.getDeviceModel() + " " + userState.getCapacity() + "\n(แอดมินโปรดตรวจรูปรอบเครื่อง 4-5 รูป)"
                );

                responseMessage = getWaitMessage("ได้รับรูปรอบเครื่องเรียบร้อยครับ 📸 แอดมินขอรับเรื่องตรวจสอบสภาพภายนอกไว้ครับ");
                break;


            // ══════════════════════════════════════════════════════════
            case "STEP_9_APPROVED_PHOTO": // แอดมินกดผ่านรูปภาพ
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("STEP_10_NAME");

                String exampleImageUrl = "https://raw.githubusercontent.com/fourwheel2005/image/main/checkiphone.jpg";
                lineMessageService.sendImage(userId, exampleImageUrl);

                responseMessage = "แอดมินตรวจสอบรูปรอบเครื่องผ่านเรียบร้อยครับ สวยมากครับ! ✨\n\n" +
                        "ถัดไป รบกวนลูกค้า **แคปหน้าจอ 'การตั้งค่า > ทั่วไป > เกี่ยวกับ'**\n" +
                        "ส่งมาให้แอดมินดูรุ่นและความจุที่แน่นอนหน่อยครับ\n" +
                        "(ตามรูปตัวอย่างที่แอดมินส่งให้ด้านบนเลยครับ ☝️)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_10_NAME": // รับรูปหน้าตั้งค่า → ขอชื่อ
                // ══════════════════════════════════════════════════════════
                if (!msg.equals("[รูปภาพ]")) {
                    responseMessage =  handleRetryLogic(userState, userId, msg, "ลูกค้าไม่ยอมส่งรูปหน้าตั้งค่า", "น้องทันใจกำลังรอรูปแคปหน้าจอตั้งค่าอยู่นะครับ 📸 รบกวนลูกค้าส่งเป็นรูปภาพเข้ามาให้หน่อยนะครับ 🙏");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("STEP_11_SUBMIT_DATA");
                responseMessage = "ได้รับข้อมูลครบถ้วนครับ 📸📱\n\n" +
                        "👉 ขั้นตอนสุดท้าย รบกวนลูกค้าพิมพ์ **ชื่อ-นามสกุล** ส่งมาให้แอดมินเพื่อใช้ในการประเมินเครดิตด้วยครับ ✍️";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_11_SUBMIT_DATA": // รับชื่อ → ส่งข้อมูลให้ Admin
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("ADMIN_MODE");
                userState.setFullName(msg);
                lineMessageService.sendAdminApprovalCard(
                        ADMIN_GROUP_ID, getServiceName(), "balloon",
                        msg + " (LINE: " + getCustomerName(userId) + ")",
                        userId,
                        "รุ่น: " + userState.getDeviceModel() + " " + userState.getCapacity()
                );
                // ลบ if-else อันเก่าออก แล้วใช้บรรทัดนี้แทน
                responseMessage = getWaitMessage("ได้รับข้อมูลครบถ้วนครับ 📝 น้องทันใจส่งเรื่องให้แอดมินตรวจสอบราคาประเมินและเครดิตให้แล้วครับ");
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_5_PRICING":
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("STEP_6_MONTH_SELECTION");
                BalloonPrice price = getPriceForModel(userState.getDeviceModel());
                if (price == null) {
                    responseMessage = getWaitMessage("สำหรับรุ่นนี้ แอดมินได้รับเรื่องเพื่อเตรียมเสนอราคาพิเศษให้แล้วครับ");
                    break;
                }
                responseMessage = "ข้อเสนอสำหรับ **iPhone " + userState.getDeviceModel() + "** มาแล้วครับ! 🎉\n" +
                        "- ยอดรับซื้อ: " + String.format("%,d", price.buyPrice()) + " บ.\n" +
                        "- 6 เดือน: งวดละ " + String.format("%,d", price.m6()) + " บ.\n" +
                        "- 8 เดือน: งวดละ " + String.format("%,d", price.m8()) + " บ.\n" +
                        "- 10 เดือน: งวดละ " + String.format("%,d", price.m10()) + " บ.\n" +
                        "- 12 เดือน: งวดละ " + String.format("%,d", price.m12()) + " บ.\n\n" +
                        "👉 ลูกค้าสนใจส่งกี่เดือนดีครับ? (พิมพ์ตัวเลข 6, 8, 10 หรือ 12 ได้เลยครับ)";
                break;

            case "STEP_6_MONTH_SELECTION":
                boolean isValidMonth = msg.matches(".*(6|8|10|12|หก|แปด|สิบ).*");
                if (!isValidMonth) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าเลือกระยะเวลาผ่อนผิด", "ลูกค้าสะดวกส่งงวดละกี่เดือนดีครับ? 😊\nมีให้เลือก: **6, 8, 10 หรือ 12 เดือน** ครับ");
                    break;
                }

                userState.setRetryCount(0);
                userState.setCurrentState("ADMIN_MODE");
                lineMessageService.sendSuccessCard(
                        ADMIN_GROUP_ID,
                        getServiceName(),
                        "balloon",
                        getCustomerName(userId),
                        userId,
                        "ลูกค้าเลือกระยะเวลา: " + msg + " เดือน"
                );
                responseMessage = getWaitMessage("รับทราบครับ! น้องทันใจได้รับข้อมูลแล้ว 📝 แอดมินจะเข้ามาสรุปยอด แจ้งเงื่อนไข และขอเอกสารทำสัญญาให้นะครับ");
                break;

            case "ADMIN_MODE":
            case "ADMIN_PHOTO_CHECK":
            case "REJECTED":
                responseMessage = null;
                break;

            default:
                userState.setCurrentState("STEP_1_INFO");
                responseMessage = "ระบบเริ่มการทำรายการใหม่ครับ กรุณาพิมพ์คำว่า 'ผ่อนบอลลูน' เพื่อเริ่มดำเนินการครับ";
                break;
        }

        if (responseMessage != null) {
            userState.setLastUserMessage(msg);
        }
        userStateRepository.save(userState);

        return responseMessage;
    }

    // ==========================================
    // 🛠️ Helper Method: ระบบจัดการคนพิมพ์มั่ว (Retry Logic)
    // ==========================================
    private String handleRetryLogic(UserState userState, String userId, String msg, String adminAlertReason, String retryPrompt) {
        int currentRetry = userState.getRetryCount() != null ? userState.getRetryCount() : 0;
        currentRetry++;

        if (currentRetry >= 2) { // 🚨 ถ้าผิดครบ 2 ครั้ง โยนเข้าโหมดแอดมินทันที
            userState.setPreviousState(userState.getCurrentState());
            userState.setCurrentState("ADMIN_MODE");
            userState.setRetryCount(0); // ล้างค่าทิ้งเพื่อรอรอบใหม่
            userStateRepository.save(userState);

            lineMessageService.sendEmergencyCard(
                    ADMIN_GROUP_ID,
                    getServiceName(),
                    "balloon",
                    getCustomerName(userId),
                    userId,
                    adminAlertReason + " เกิน 2 ครั้ง (ข้อความล่าสุด: " + msg + ")"
            );

            // ก่อนบรรทัด return ใน if (currentRetry >= 2)
            return getWaitMessage("น้องทันใจดูเหมือนจะยังไม่เข้าใจข้อมูลส่วนนี้ 😅 เพื่อความรวดเร็ว น้องขอส่งเรื่องให้แอดมินมาช่วยดูแลเคสนี้ให้นะครับ");
        }

        // ถ้ายิ่งไม่ครบ 2 ครั้ง ให้บันทึกแต้มสะสม และถามคำถามเดิมซ้ำ
        userState.setRetryCount(currentRetry);
        return retryPrompt;
    }

    private String getCustomerName(String userId) {
        try { return messagingApiClient.getProfile(userId).get().body().displayName(); } catch (Exception e) { return "ลูกค้า"; }
    }

    private BalloonPrice getPriceForModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) return null;
        String m = modelName.toLowerCase().replace("iphone ", "").trim();
        return switch (m) {
            case "12"         -> new BalloonPrice(3500,  1190,  890,  790,  690);
            case "12 pro"     -> new BalloonPrice(4000,  1290, 1090,  890,  790);
            case "12 pro max" -> new BalloonPrice(4000,  1290, 1090,  890,  790);
            case "13 mini"    -> new BalloonPrice(3500,  1190,  890,  790,  690);
            case "13"         -> new BalloonPrice(5000,  1590, 1290, 1090,  990);
            case "13 pro"     -> new BalloonPrice(7000,  2290, 1790, 1590, 1390);
            case "13 pro max" -> new BalloonPrice(9000,  2890, 2290, 1990, 1790);
            case "14"         -> new BalloonPrice(7000,  2290, 1790, 1590, 1390);
            case "14 plus"    -> new BalloonPrice(9000,  2890, 2290, 1990, 1790);
            case "14 pro"     -> new BalloonPrice(9000,  2890, 2290, 1990, 1790);
            case "14 pro max" -> new BalloonPrice(11000, 3550, 2750, 2350, 2150);
            case "15"         -> new BalloonPrice(10000, 3190, 2590, 2190, 1990);
            case "15 plus"    -> new BalloonPrice(11000, 3550, 2750, 2350, 2150);
            case "15 pro"     -> new BalloonPrice(12000, 3850, 3050, 2550, 2350);
            case "15 pro max" -> new BalloonPrice(13000, 4190, 3290, 2790, 2490);
            case "16e"        -> new BalloonPrice(8000,  2550, 2050, 1750, 1550);
            case "16"         -> new BalloonPrice(11000, 3550, 2750, 2350, 2150);
            case "16 plus"    -> new BalloonPrice(13000, 4190, 3290, 2790, 2490);
            case "16 pro"     -> new BalloonPrice(15000, 4790, 3790, 3290, 2890);
            case "16 pro max" -> new BalloonPrice(18000, 5690, 4590, 3990, 3490);
            case "17"         -> new BalloonPrice(16000, 5090, 3990, 3490, 3090);
            case "17 air"     -> new BalloonPrice(15000, 4790, 3790, 3290, 2890);
            case "17 pro"     -> new BalloonPrice(21000, 6690, 5290, 4620, 4190);
            case "17 pro max" -> new BalloonPrice(25000, 7990, 6290, 5390, 4790);
            default -> null;
        };
    }


    private boolean isBusinessHours() {
        // เวลาปัจจุบันในประเทศไทย
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Bangkok"));

        // 🔴 ตั้งเวลาเปิดร้าน (08:30 น.)
        LocalTime openTime = LocalTime.of(8, 30);

        // 🔴 ตั้งเวลาปิดร้าน (19:00 น.)
        LocalTime closeTime = LocalTime.of(19, 0);

        // คืนค่า true ถ้าร้านเปิดอยู่
        return !now.isBefore(openTime) && !now.isAfter(closeTime);
    }

    // ==========================================
    // 🛠️ Helper Method: จัดการข้อความรอแอดมิน (แยกกลางวัน-กลางคืน)
    // ==========================================
    private String getWaitMessage(String prefixText) {
        if (isBusinessHours()) {
            return prefixText + " รบกวนรอสักครู่นะครับ ⏳";
        } else {
            return prefixText + "\n\nแต่ตอนนี้นอกเวลาทำการแล้ว (เปิด 08:30 - 19:00 น.) พรุ่งนี้เช้าเวลา 08:30 น. แอดมินจะรีบเข้ามาดูแลให้เป็นคิวแรกเลยนะครับ 🙏💤";
        }
    }
}