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
        return "รีบอลลูน".equals(serviceName);
    }

    @Override
    public String getServiceName() { return "รีบอลลูน"; }

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
            lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), getCustomerName(userId), "ลูกค้าต้องการคุยกับคน/หงุดหงิดบอท");
            return "รับทราบครับ น้องทันใจขออภัยในความไม่สะดวกนะครับ 🙏 เดี๋ยวแอดมินตัวจริงรีบเข้ามาดูแลเคสนี้ให้ทันที รบกวนรอสักครู่นะครับ ⏳";
        }

        String responseMessage = null;

        switch (state) {

            // ══════════════════════════════════════════════════════════
            case "STEP_1_INFO": // เริ่มต้น → ถามรุ่น
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("STEP_2_CAPACITY");
                responseMessage = "สวัสดีครับ 🙏😊 น้องทันใจยินดีให้บริการรีบอลลูนครับ ขออนุญาตสอบถามข้อมูลเบื้องต้นนะครับ\n" +
                        "👉 ลูกค้าใช้ไอโฟน **รุ่นไหน** ครับ? (เช่น 13 Pro Max, 15 Pro, 16)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_2_CAPACITY": // รับรุ่น → ถามความจุ
                // ══════════════════════════════════════════════════════════
                log.info("🤖 [รีบอลลูน] STEP_2 AI สกัดรุ่นจาก: {}", msg);
                ExtractedData modelData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedModel = modelData.deviceModel();

                if (extractedModel == null || "unknown".equalsIgnoreCase(extractedModel)) {
                    responseMessage = "น้องทันใจยังไม่ทราบรุ่นเลยครับ 😅 รบกวนแจ้ง 'รุ่นไอโฟน' เช่น 13 Pro Max หรือ 15 Pro อีกครั้งนะครับ 📱";
                    break;
                }

                userState.setDeviceModel(extractedModel);
                userState.setCurrentState("STEP_3_PROVINCE");
                responseMessage = "รับทราบครับ รุ่น **iPhone " + extractedModel + "** นะครับ! 📱\n" +
                        "👉 สนใจความจุ **กี่ GB** ดีครับ? (เช่น 128, 256, 512)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_3_PROVINCE": // รับความจุ → ถามจังหวัด
                // ══════════════════════════════════════════════════════════
                log.info("🤖 [รีบอลลูน] STEP_3 AI สกัดความจุจาก: {}", msg);
                ExtractedData capData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedCapacity = capData.capacity();

                if (extractedCapacity == null || "unknown".equalsIgnoreCase(extractedCapacity)) {
                    responseMessage = "รบกวนระบุความจุอีกครั้งนะครับ 🙏 เช่น 128GB, 256GB ครับ";
                    break;
                }

                userState.setCapacity(extractedCapacity);
                userState.setCurrentState("STEP_4_AGE");
                responseMessage = "ความจุ **" + extractedCapacity + "** นะครับ! ✨\n" +
                        "👉 ลูกค้าอยู่ **จังหวัด** อะไรครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_4_AGE": // รับจังหวัด → ถามอายุ
                // ══════════════════════════════════════════════════════════
                userState.setCurrentState("STEP_5_REPAIR");
                responseMessage = "โอเคครับ 📍 แล้วลูกค้า **อายุ** เท่าไหร่ครับ?";
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
                    responseMessage = "รบกวนระบุตัวเลขอายุให้น้องทันใจหน่อยนะครับ (เช่น 25)";
                    break;
                }

                if (extractedAge < 18) {
                    userState.setCurrentState("ADMIN_MODE");
                    lineMessageService.sendEmergencyCard(
                            ADMIN_GROUP_ID, getServiceName(), getCustomerName(userId),
                            "⚠️ ลูกค้าอายุต่ำกว่าเกณฑ์: " + extractedAge + " ปี — แนะนำให้ผู้ปกครองเป็นคนดำเนินการแทน"
                    );
                    responseMessage = "ขอบคุณที่แจ้งนะครับ 🙏\n\nเกณฑ์อายุที่กำหนดอยู่ที่ **18 - 55 ปี** รบกวนลูกค้ารอแอดมินมาพิจารณาสักครู่นะครับ ⏳";
                    break;
                }

                if (extractedAge > 55) {
                    userState.setCurrentState("ADMIN_MODE");
                    lineMessageService.sendEmergencyCard(
                            ADMIN_GROUP_ID, getServiceName(), getCustomerName(userId),
                            "⚠️ ลูกค้าอายุเกินเกณฑ์: " + extractedAge + " ปี — กรุณาพิจารณาจากงาน / Statement / เงินเดือนของลูกค้าครับ"
                    );
                    responseMessage = "ขอบคุณที่แจ้งนะครับ 🙏\n\nเกณฑ์อายุที่กำหนดอยู่ที่ **18 - 55 ปี** รบกวนลูกค้ารอแอดมินมาพิจารณาสักครู่นะครับ ⏳";
                    break;
                }

                userState.setCurrentState("STEP_6_FACEID");
                responseMessage = "อายุ " + extractedAge + " ปี รับทราบครับ 👍\n\n" +
                        "ถัดไปน้องทันใจขอเช็คประวัติเครื่องหน่อยครับ 🔍\n" +
                        "👉 เครื่องเคยแกะซ่อม หรือเปลี่ยนชิ้นส่วนใดๆ มาไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_6_FACEID": // ตรวจซ่อม → ถาม Face ID
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer repairAns = aiScreeningService.interpret(msg, lastMessage);
                if (repairAns == ScreeningAnswer.YES) {
                    userState.setCurrentState("REJECTED");
                    responseMessage = "ต้องขออภัยด้วยนะครับ 🙏 ทางร้านขอสงวนสิทธิ์ไม่รับเครื่องที่ผ่านการแกะซ่อมครับ หากมีข้อสงสัยพิมพ์หาแอดมินได้เลยครับ";
                    break;
                }
                if (repairAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = "รบกวนตอบให้น้องทันใจชัดเจนอีกนิดครับ เช่น 'ไม่เคยแกะเลยครับ' หรือ 'เคยเปลี่ยนแบตครับ'";
                    break;
                }
                userState.setCurrentState("STEP_7_INSTALLMENT");
                responseMessage = "โอเคครับ 👍 แล้ว **Face ID (สแกนหน้า)** ใช้งานได้ปกติไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_7_INSTALLMENT": // ตรวจ Face ID → ถามติดผ่อน
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer faceIdAns = aiScreeningService.interpret(msg, lastMessage);
                if (faceIdAns == ScreeningAnswer.NO) {
                    userState.setCurrentState("REJECTED");
                    responseMessage = "ต้องขออภัยด้วยนะครับ 🙏 ทางร้านไม่สามารถรับเครื่องที่สแกนหน้าไม่ได้ครับ";
                    break;
                }
                if (faceIdAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = "รบกวนตอบให้น้องทันใจทราบชัดๆ นิดนึงครับ เช่น 'ปกติครับ' หรือ 'สแกนไม่ได้ค่ะ'";
                    break;
                }

                // ✅ ส่งไป STEP_8_DEVICE_PHOTOS เพื่อรอรับคำตอบเรื่องติดผ่อน
                userState.setCurrentState("STEP_8_DEVICE_PHOTOS");
                responseMessage = "เยี่ยมเลยครับ 😊 แล้วเครื่องมี **ติดผ่อนกับร้านอื่น หรือติดใส่ iCloud ร้านอื่น** ไหมครับ?";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_8_DEVICE_PHOTOS": // ตรวจติดผ่อน → ขอรูปรอบเครื่อง 4-5 รูป
                // ══════════════════════════════════════════════════════════
                ScreeningAnswer installAns = aiScreeningService.interpret(msg, lastMessage);
                if (installAns == ScreeningAnswer.YES) {
                    userState.setCurrentState("REJECTED");
                    responseMessage = "ต้องขออภัยครับ 🙏 น้องทันใจไม่สามารถรับเครื่องที่ยังติดภาระผ่อนหรือล็อค iCloud ครับ";
                    break;
                }
                if (installAns == ScreeningAnswer.UNCLEAR) {
                    responseMessage = "รบกวนยืนยันใหม่อีกครั้งครับ เช่น 'ไม่ติดผ่อนครับ'";
                    break;
                }

                // ✅ ส่งไป STEP_9_SETTINGS_PHOTO เพื่อรอรับรูป 4-5 รูป (Hybrid Approach)
                userState.setCurrentState("STEP_9_SETTINGS_PHOTO");
                responseMessage = "ผ่านการตรวจสอบเบื้องต้นเรียบร้อยครับ 🎉✅\n\n" +
                        "เพื่อให้แอดมินประเมินสภาพภายนอกได้ชัดเจน รบกวนลูกค้า:\n" +
                        "📸 **ถ่ายรูปรอบเครื่อง 4-5 รูป** (หน้า-หลัง-ข้าง)\n" +
                        "ส่งมาให้น้องทันใจดูสภาพหน่อยครับ (สามารถถ่ายผ่านกระจกเงาได้ครับ🪞)✨";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_9_SETTINGS_PHOTO": // รับรูปรอบเครื่อง → ขอรูปหน้าตั้งค่า (พร้อมตัวอย่าง)
                // ══════════════════════════════════════════════════════════
                // 🛡️ ดักรอรูป Batch หรือคำว่าครบแล้ว
                boolean isImageBatchReceived = msg.equals("[รูปภาพ]") ||
                        msg.contains("ครบ") ||
                        msg.contains("ส่งแล้ว") ||
                        msg.contains("เรียบร้อย");

                if (!isImageBatchReceived) {
                    return "น้องทันใจกำลังรอรูปรอบเครื่องอยู่นะครับ 📸 ทยอยส่งมาได้เลยครับ\n" +
                            "(หากส่งรูปครบแล้ว พิมพ์บอกแอดมินว่า 'ครบแล้ว' ได้เลยครับ ✨)";
                }

                // ✅ ส่งไป STEP_10_NAME เพื่อรอรับรูปแคปหน้าจอตั้งค่า
                userState.setCurrentState("STEP_10_NAME");
                String exampleImageUrl = "https://raw.githubusercontent.com/fourwheel2005/image/main/S__8298515.jpg";
                lineMessageService.sendImage(userId, exampleImageUrl);

                responseMessage = "ได้รับรูปรอบเครื่องเรียบร้อยครับ สวยมากครับ! ✨\n\n" +
                        "ถัดไป รบกวนลูกค้า **แคปหน้าจอ 'การตั้งค่า > ทั่วไป > เกี่ยวกับ'**\n" +
                        "ส่งมาให้แอดมินดูรุ่นและความจุที่แน่นอนหน่อยครับ\n" +
                        "(ตามรูปตัวอย่างที่แอดมินส่งให้ด้านบนเลยครับ ☝️)";
                break;

            // ══════════════════════════════════════════════════════════
            case "STEP_10_NAME": // รับรูปหน้าตั้งค่า → ขอชื่อ
                // ══════════════════════════════════════════════════════════
                // 🛡️ บังคับว่าต้องส่งเป็นรูปภาพเท่านั้น
                if (!msg.equals("[รูปภาพ]")) {
                    return "น้องทันใจกำลังรอรูปแคปหน้าจอตั้งค่าอยู่นะครับ 📸 รบกวนลูกค้าส่งเป็นรูปภาพเข้ามาให้หน่อยนะครับ 🙏";
                }

                // ✅ รับรูปแล้ว ส่งไป STEP 11 เพื่อรอรับชื่อ
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
                        ADMIN_GROUP_ID, "รีบอลลูน", "balloon",
                        msg + " (LINE: " + getCustomerName(userId) + ")",
                        userId,
                        "รุ่น: " + userState.getDeviceModel() + " " + userState.getCapacity()
                );
                responseMessage = "ได้รับข้อมูลครบถ้วนครับ 📝 น้องทันใจส่งเรื่องให้แอดมินตรวจสอบราคาประเมินและเครดิตให้แล้วครับ รบกวนรอสักครู่นะครับ ⏳";
                break;

            // ══════════════════════════════════════════════════════════
            // Admin-triggered states (ชื่อคงเดิม ไม่แตะ)
            // ══════════════════════════════════════════════════════════
            case "STEP_5_PRICING":
                userState.setCurrentState("STEP_6_MONTH_SELECTION");
                BalloonPrice price = getPriceForModel(userState.getDeviceModel());
                if (price == null) {
                    responseMessage = "สำหรับรุ่นนี้ รบกวนรอแอดมินเข้ามาเสนอราคาพิเศษให้นะครับ ⏳";
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
                    responseMessage = "ลูกค้าสะดวกส่งงวดละกี่เดือนดีครับ? 😊\nมีให้เลือก: **6, 8, 10 หรือ 12 เดือน** ครับ";
                    break;
                }
                userState.setCurrentState("ADMIN_MODE");
                lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, "รีบอลลูน", getCustomerName(userId), "ลูกค้าเลือกระยะเวลา: " + msg + " เดือน");
                responseMessage = "รับทราบครับ! น้องทันใจได้รับข้อมูลแล้ว 📝\nเดี๋ยวแอดมินตัวจริงจะเข้ามาสรุปยอด แจ้งเงื่อนไข และขอเอกสารทำสัญญาให้นะครับ รบกวนรอสักครู่ครับ ⏳";
                break;

            case "ADMIN_MODE":
            case "REJECTED":
                responseMessage = null;
                break;

            default:
                userState.setCurrentState("STEP_1_INFO");
                responseMessage = "ระบบเริ่มการทำรายการใหม่ครับ กรุณาพิมพ์คำว่า 'รีบอลลูน' เพื่อเริ่มดำเนินการครับ";
                break;
        }

        if (responseMessage != null) {
            userState.setLastUserMessage(msg);
        }
        userStateRepository.save(userState);

        return responseMessage;
    }

    private String getCustomerName(String userId) {
        try { return messagingApiClient.getProfile(userId).get().body().displayName(); } catch (Exception e) { return "ลูกค้า"; }
    }

    private BalloonPrice getPriceForModel(String modelName) {
        if (modelName == null) return null;
        String m = modelName.toLowerCase().replace("iphone ", "").trim();
        return switch (m) {
            case "12 mini"    -> new BalloonPrice(3500,  1190,  890,  790,  690);
            case "12"         -> new BalloonPrice(4000,  1290, 1090,  890,  790);
            case "12 pro"     -> new BalloonPrice(4500,  1490, 1190,  990,  890);
            case "12 pro max" -> new BalloonPrice(5000,  1590, 1290, 1090,  990);
            case "13 mini"    -> new BalloonPrice(4500,  1490, 1190,  990,  890);
            case "13"         -> new BalloonPrice(6000,  1990, 1590, 1290, 1190);
            case "13 pro"     -> new BalloonPrice(7000,  2290, 1790, 1590, 1390);
            case "13 pro max" -> new BalloonPrice(9000,  2890, 2290, 1990, 1790);
            case "14"         -> new BalloonPrice(8000,  2550, 2050, 1750, 1550);
            case "14 plus"    -> new BalloonPrice(9000,  2850, 2250, 1950, 1750);
            case "14 pro"     -> new BalloonPrice(11000, 3550, 2750, 2350, 2150);
            case "14 pro max" -> new BalloonPrice(12000, 3850, 3050, 2550, 2350);
            case "15"         -> new BalloonPrice(11000, 3550, 2750, 2390, 2150);
            case "15 plus"    -> new BalloonPrice(12000, 3850, 3050, 2550, 2350);
            case "15 pro"     -> new BalloonPrice(13000, 4190, 3290, 2790, 2490);
            case "15 pro max" -> new BalloonPrice(15000, 4790, 3790, 3290, 2890);
            case "16e"        -> new BalloonPrice(10000, 3190, 2590, 2190, 1990);
            case "16"         -> new BalloonPrice(13000, 4190, 3290, 2790, 2490);
            case "16 plus"    -> new BalloonPrice(15000, 4790, 3750, 3290, 2890);
            case "16 pro"     -> new BalloonPrice(17000, 5390, 4290, 3690, 3290);
            case "16 pro max" -> new BalloonPrice(20000, 6390, 4990, 4290, 3890);
            case "17"         -> new BalloonPrice(16000, 5090, 3990, 3490, 3090);
            case "17 air"     -> new BalloonPrice(20000, 6390, 4990, 4390, 3890);
            case "17 pro"     -> new BalloonPrice(22000, 6990, 5490, 4790, 4290);
            case "17 pro max" -> new BalloonPrice(25000, 7990, 6290, 5390, 4790);
            default -> null;
        };
    }
}