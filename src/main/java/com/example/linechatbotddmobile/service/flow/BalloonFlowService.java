package com.example.linechatbotddmobile.service.flow;

import com.example.linechatbotddmobile.config.AdminGroup;
import com.example.linechatbotddmobile.dto.ExtractedData;
import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiDataExtractorService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService.ScreeningAnswer;
import com.example.linechatbotddmobile.service.line.LineMessageService;
import com.example.linechatbotddmobile.service.line.LineProfileService;
import com.example.linechatbotddmobile.util.IphoneModelPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalloonFlowService implements ServiceFlowHandler {

    private static final ZoneId BANGKOK_ZONE = ZoneId.of("Asia/Bangkok");
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final LocalTime SHOP_OPEN_TIME = LocalTime.of(8, 30);
    private static final LocalTime SHOP_CLOSE_TIME = LocalTime.of(19, 0);

    private final UserStateRepository userStateRepository;
    private final LineMessageService lineMessageService;
    private final AiDataExtractorService aiDataExtractorService;
    private final AiScreeningService aiScreeningService;
    private final LineProfileService lineProfileService;

    private final String ADMIN_GROUP_ID = AdminGroup.MAIN_GROUP_ID;

    // 📸 รูปตัวอย่างการถ่ายรอบเครื่อง (ของเดิม)
    // ⚠️ ห้ามใช้ raw.githubusercontent.com เสิร์ฟรูปให้ LINE — GitHub rate-limit เป็น 429
    //    เมื่อโดนดึงถี่ ๆ → รูปที่ LINE ยังไม่เคย cache จะขึ้นกากบาท X (โดยเฉพาะ step ที่ส่ง 2 รูปพร้อมกัน)
    //    ใช้ jsDelivr CDN (ดึงจากรีโปเดิม ไฟล์อยู่ที่เดิม) ซึ่งออกแบบมาให้ hotlink และไม่ rate-limit
    private static final String DEVICE_PHOTO_EXAMPLE_IMAGE_URL =
            "https://cdn.jsdelivr.net/gh/fourwheel2005/image@main/checkiphone.jpg";

    // 🪞 คู่มือ "วิธีถ่ายรูปเครื่องหน้ากระจก" (สำหรับลูกค้าที่มีมือถือเครื่องเดียว)
    private static final String MIRROR_PHOTO_GUIDE_IMAGE_URL =
            "https://cdn.jsdelivr.net/gh/fourwheel2005/image@main/mirror-photo-guide.jpg";

    // 📅 งวดผ่อนทั้งหมดที่ทางร้านเปิดให้ เรียงจากสั้นไปยาว
    //    ใช้เป็น "หัวตาราง" ของ price() — ค่าที่ส่งเข้ามาจะไล่ตามลำดับนี้ซ้ายไปขวาเหมือนอ่านตารางราคา
    private static final List<Integer> INSTALLMENT_MONTHS = List.of(6, 8, 10, 12, 15, 18, 21, 24);

    // 🔢 คำอ่านจำนวนงวดภาษาไทย (เรียงคำยาวก่อน เพื่อไม่ให้ "สิบ" ไปกินคำว่า "สิบแปด")
    private static final Map<String, Integer> THAI_MONTH_WORDS = buildThaiMonthWords();

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    /**
     * ราคาผ่อนบอลลูนของ 1 รุ่น
     *
     * monthlyPayments เก็บ "เฉพาะงวดที่รุ่นนั้นเปิดให้เลือกจริง" เรียงจากสั้นไปยาว
     * เพราะตารางราคาใหม่ให้จำนวนงวดไม่เท่ากันในแต่ละรุ่น
     * (13 mini มีแค่ 6-12 งวด, 14 มีถึง 18 งวด, ส่วน 16 Pro ขึ้นไปมีถึง 24 งวด)
     */
    public record BalloonPrice(int buyPrice, Map<Integer, Integer> monthlyPayments) {

        public BalloonPrice {
            monthlyPayments = Collections.unmodifiableMap(new LinkedHashMap<>(monthlyPayments));
        }

        public boolean supportsMonth(int months) {
            return monthlyPayments.containsKey(months);
        }

        public Integer paymentFor(int months) {
            return monthlyPayments.get(months);
        }
    }

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
            clearFollowUpReminder(userState);
            userStateRepository.save(userState);
            lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId, "ลูกค้าต้องการคุยกับแอดมินนุด🫶🏻🥳💵");

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
                if (IphoneModelPolicy.isUnsupportedBelowIphone12Message(msg)) {
                    userState.setRetryCount(0);
                    responseMessage = IphoneModelPolicy.UNSUPPORTED_BELOW_IPHONE_12_MESSAGE;
                    break;
                }

                ExtractedData modelData = aiDataExtractorService.extractInfo(msg, lastMessage);
                String extractedModel = modelData.deviceModel();

                if (extractedModel == null || "unknown".equalsIgnoreCase(extractedModel)) {
                    extractedModel = guessModelFromRawMessage(msg);
                }

                if (extractedModel == null || "unknown".equalsIgnoreCase(extractedModel)) {
                    responseMessage = handleRetryLogic(userState, userId, msg, "บอทสกัดรุ่นโทรศัพท์ไม่ได้", "น้องทันใจยังไม่ทราบรุ่นเลยครับ 😅 รบกวนแจ้ง 'รุ่นไอโฟน' เช่น 13 Pro Max หรือ 15 Pro อีกครั้งนะครับ 📱");
                    break;
                }

                if (IphoneModelPolicy.isUnsupportedBelowIphone12Model(extractedModel)) {
                    userState.setRetryCount(0);
                    responseMessage = IphoneModelPolicy.UNSUPPORTED_BELOW_IPHONE_12_MESSAGE;
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
                // ส่งรูปตัวอย่างรอบเครื่อง + คู่มือถ่ายผ่านกระจกเงา "คู่กัน" ในข้อความเดียว
                lineMessageService.sendImages(userId, List.of(
                        DEVICE_PHOTO_EXAMPLE_IMAGE_URL,
                        MIRROR_PHOTO_GUIDE_IMAGE_URL
                ));
                responseMessage = "ผ่านการตรวจสอบเบื้องต้นเรียบร้อยครับ 🎉✅\n\n" +
                        "เพื่อให้แอดมินประเมินสภาพภายนอกได้ชัดเจน รบกวนลูกค้า:\n" +
                        "📸 **ถ่ายรูปรอบเครื่อง 4-5 รูป** (หน้า-หลัง-ข้าง)\n" +
                        "ส่งมาให้น้องทันใจดูสภาพหน่อยครับ\n" +
                        "🪞 ลูกค้าที่มีมือถือเครื่องเดียว ถ่ายผ่านกระจกเงาได้เลยครับ (ดูวิธีตามรูปคู่มือที่แนบให้ด้านบนได้เลยครับ)✨";
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
                userState.setCurrentState("STEP_10_NAME");

                String exampleImageUrl2 = "https://cdn.jsdelivr.net/gh/fourwheel2005/image@main/capsystem.jpg";
                lineMessageService.sendImage(userId, exampleImageUrl2);

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
                BalloonPrice price = getPriceForModel(userState.getDeviceModel());

                if (price == null) {
                    // ไม่มีรุ่นนี้ในตารางราคา (เช่น iPhone 12 ที่ถอดออกจากตารางแล้ว) → ห้ามเดาราคาเอง
                    // เดิมโค้ดดันย้าย state ไป STEP_6 ทั้งที่ยังไม่ได้เสนอราคา ลูกค้าเลยค้างอยู่หน้าที่ไม่มีตัวเลือกให้เลือก
                    userState.setPreviousState(state);
                    userState.setCurrentState("ADMIN_MODE");
                    lineMessageService.sendEmergencyCard(ADMIN_GROUP_ID, getServiceName(), "balloon", getCustomerName(userId), userId,
                            "⚠️ รุ่นนี้ไม่มีในตารางราคา รบกวนแอดมินเสนอราคาเองครับ\nรุ่น: "
                                    + userState.getDeviceModel() + " " + userState.getCapacity());
                    responseMessage = getWaitMessage("สำหรับรุ่นนี้ แอดมินได้รับเรื่องเพื่อเตรียมเสนอราคาพิเศษให้แล้วครับ");
                    break;
                }

                userState.setCurrentState("STEP_6_MONTH_SELECTION");
                StringBuilder offer = new StringBuilder()
                        .append("ข้อเสนอสำหรับ **iPhone ").append(userState.getDeviceModel()).append("** มาแล้วครับ! 🎉\n")
                        .append("- ยอดรับซื้อ: ").append(formatBaht(price.buyPrice())).append(" บ.\n");
                // ไล่เฉพาะงวดที่รุ่นนี้มีจริง — รุ่นเล็กจะได้ไม่โชว์ 15/18/21/24 ที่ร้านไม่ได้เปิดให้
                price.monthlyPayments().forEach((months, amount) ->
                        offer.append("- ").append(months).append(" เดือน: งวดละ ").append(formatBaht(amount)).append(" บ.\n"));
                offer.append("\n👉 ลูกค้าสนใจส่งกี่เดือนดีครับ? (พิมพ์ตัวเลข ")
                        .append(formatMonthChoices(price))
                        .append(" ได้เลยครับ)");
                responseMessage = offer.toString();
                break;

            case "STEP_6_MONTH_SELECTION":
                BalloonPrice selectedModelPrice = getPriceForModel(userState.getDeviceModel());
                Integer selectedMonths = parseSelectedMonth(msg, selectedModelPrice);

                if (selectedMonths == null) {
                    String monthChoices = selectedModelPrice != null ? formatMonthChoices(selectedModelPrice) : "6, 8, 10 หรือ 12";
                    responseMessage = handleRetryLogic(userState, userId, msg, "ลูกค้าเลือกระยะเวลาผ่อนผิด",
                            "ลูกค้าสะดวกส่งงวดละกี่เดือนดีครับ? 😊\nมีให้เลือก: **" + monthChoices + " เดือน** ครับ");
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
                        "รุ่น: " + userState.getDeviceModel() + " " + userState.getCapacity()
                                + "\nลูกค้าเลือกระยะเวลา: " + selectedMonths + " เดือน"
                                + " (งวดละ " + formatBaht(selectedModelPrice.paymentFor(selectedMonths)) + " บ.)"
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
        updateFollowUpReminder(userState, responseMessage != null);
        userStateRepository.save(userState);

        return responseMessage;
    }

    // ==========================================
    // 🛠️ Helper Method: ระบบจัดการคนพิมพ์มั่ว (Retry Logic)
    // ==========================================
    private String handleRetryLogic(UserState userState, String userId, String msg, String adminAlertReason, String retryPrompt) {
        int currentRetry = userState.getRetryCount() != null ? userState.getRetryCount() : 0;
        currentRetry++;

        if (currentRetry >= MAX_RETRY_ATTEMPTS) { // 🚨 ถ้าผิดครบ 2 ครั้ง โยนเข้าโหมดแอดมินทันที
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
        return lineProfileService.getDisplayName(userId);
    }

    private void updateFollowUpReminder(UserState userState, boolean botAskedCustomerToContinue) {
        if (!isFollowUpReminderState(userState.getCurrentState())) {
            clearFollowUpReminder(userState);
            return;
        }

        if (botAskedCustomerToContinue) {
            userState.setFollowUpReminderStartedAt(LocalDateTime.now(BANGKOK_ZONE));
            userState.setFollowUpReminderSent(false);
        }
    }

    private boolean isFollowUpReminderState(String currentState) {
        return "STEP_9_SETTINGS_PHOTO".equals(currentState)
                || "STEP_10_NAME".equals(currentState);
    }

    private void clearFollowUpReminder(UserState userState) {
        userState.setFollowUpReminderStartedAt(null);
        userState.setFollowUpReminderSent(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 💰 ตารางราคาผ่อนบอลลูน — เรียงแถวตามตารางราคาของร้าน (13 mini → 17 Pro Max)
    //
    //    ลำดับตัวเลขหลัง "รับซื้อ" = งวด 6, 8, 10, 12, 15, 18, 21, 24 (INSTALLMENT_MONTHS)
    //    ใส่เท่าที่รุ่นนั้นมีในตาราง แล้วหยุด — รุ่นที่ตารางเว้นว่างไว้จะไม่ถูกเสนอให้ลูกค้าเลือก
    //
    //    ℹ️ iPhone 12 ไม่มีอยู่ในตารางราคาใบใหม่ แต่ทางร้านยังรับอยู่ จึงคงเรทเดิมไว้
    //       (มีแค่งวด 6-12 เหมือนเดิม ไม่ได้เปิดงวดยาว 15/18/21/24 ให้)
    // ══════════════════════════════════════════════════════════════════════
    private BalloonPrice getPriceForModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) return null;
        String m = normalizeModelName(modelName);
        return switch (m) {
            //                    รับซื้อ     6     8     10    12    15    18    21    24
            case "12"         -> price(3500,  1190,  890,  790,  690);   // เรทเดิม
            case "12 pro"     -> price(4000,  1290, 1090,  890,  790);   // เรทเดิม
            case "12 pro max" -> price(4000,  1290, 1090,  890,  790);   // เรทเดิม
            case "13 mini"    -> price(3500,  1190,  890,  790,  690);
            case "13"         -> price(5000,  1590, 1290, 1090,  990);
            case "13 pro"     -> price(7000,  2290, 1790, 1590, 1390,  950);
            case "13 pro max" -> price(9000,  2890, 2290, 1990, 1790, 1190);
            case "14"         -> price(7000,  2290, 1790, 1590, 1390, 1490, 1290);
            case "14 plus"    -> price(9000,  2890, 2290, 1990, 1790, 1250, 1050);
            case "14 pro"     -> price(9000,  2890, 2290, 1990, 1790, 1490, 1290);
            case "14 pro max" -> price(11000, 3550, 2750, 2350, 2150, 1750, 1550);
            case "15"         -> price(10000, 3190, 2590, 2190, 1990, 1590, 1390);
            case "15 plus"    -> price(11000, 3550, 2750, 2350, 2150, 1750, 1550);
            case "15 pro"     -> price(12000, 3850, 3050, 2550, 2350, 1950, 1650);
            case "15 pro max" -> price(13000, 4190, 3290, 2790, 2490, 2090, 1790);
            case "16"         -> price(11000, 3550, 2750, 2350, 2150, 1750, 1550);
            case "16e"        -> price(8000,  2550, 2050, 1750, 1550, 1250, 1050);
            case "16 plus"    -> price(13000, 4190, 3290, 2790, 2490, 2090, 1790);
            case "16 pro"     -> price(15000, 4790, 3790, 3290, 2890, 2390, 2090, 1890, 1690);
            case "16 pro max" -> price(18000, 5690, 4590, 3990, 3490, 2890, 2490, 2190, 1990);
            case "17"         -> price(16000, 5090, 3990, 3490, 3090, 2590, 2290, 1990, 1790);
            case "17 air"     -> price(15000, 4790, 3790, 3290, 2890, 2390, 2090, 1890, 1690);
            case "17 pro"     -> price(21000, 6950, 5550, 4650, 4050, 3350, 2950, 2550, 2350);
            case "17 pro max" -> price(25000, 8350, 6550, 5550, 4790, 3990, 3490, 3090, 2790);
            default -> null;
        };
    }

    /**
     * สร้างราคา 1 รุ่นจากตัวเลขที่อ่านจากตารางซ้ายไปขวา
     * ใส่กี่ตัวก็ได้ (4-8 ตัว) ระบบจะจับคู่กับงวดใน INSTALLMENT_MONTHS ตามลำดับให้เอง
     */
    private static BalloonPrice price(int buyPrice, int... monthlyAmounts) {
        if (monthlyAmounts.length == 0 || monthlyAmounts.length > INSTALLMENT_MONTHS.size()) {
            throw new IllegalArgumentException("จำนวนงวดต้องอยู่ระหว่าง 1-" + INSTALLMENT_MONTHS.size() + " ค่า");
        }
        LinkedHashMap<Integer, Integer> payments = new LinkedHashMap<>();
        for (int i = 0; i < monthlyAmounts.length; i++) {
            payments.put(INSTALLMENT_MONTHS.get(i), monthlyAmounts[i]);
        }
        return new BalloonPrice(buyPrice, payments);
    }

    /**
     * เดารุ่นจากข้อความดิบ ใช้เป็นตาข่ายรับเมื่อ AI สกัดรุ่นไม่ได้ (OpenAI ล่ม / ตอบ unknown)
     *
     * ⚠️ ของเดิมเทียบ msg.contains("pro max") กับข้อความที่ยังไม่ได้ lowercase
     *    ลูกค้าพิมพ์ "13 Pro Max" (ตัวใหญ่ตามปกติ) จะกลายเป็นรุ่น "13" เฉยๆ
     *    → เสนอราคาผิดเป็น 5,000 แทนที่จะเป็น 9,000
     *    และเช็ค contains("p") ก่อน "plus" ทำให้ "14 Plus" กลายเป็น "14 Pro" อีกด้วย
     *    (บั๊กนี้เจอตอนยิง response ทดสอบ ไม่ได้เกิดจากการแก้ราคารอบนี้)
     */
    private String guessModelFromRawMessage(String msg) {
        String m = msg.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!m.matches("^1[1-7].*")) return null;

        String base = m.substring(0, 2);
        // เรียงจากคำเฉพาะเจาะจงที่สุดก่อน — "plus" ต้องมาก่อน "p" ไม่งั้นกลายเป็น Pro
        if (base.equals("16") && m.matches("^16\\s*e\\b.*")) return "16e";
        if (m.contains("pro max") || m.contains("promax") || m.contains("pm")) return base + " Pro Max";
        if (m.contains("plus")) return base + " Plus";
        if (m.contains("mini")) return base + " mini";
        if (m.contains("air")) return base + " Air";
        if (m.contains("pro") || m.matches("^1[1-7]\\s*p\\b.*")) return base + " Pro";
        return base;
    }

    /**
     * ปรับชื่อรุ่นให้ตรงกับคีย์ในตารางราคา
     * รองรับที่ลูกค้า/AI ส่งมาหลายแบบ เช่น "iPhone 15 Pro", "iphone15pro" (ติดกัน), "13 ProMax"
     */
    private String normalizeModelName(String modelName) {
        return modelName.toLowerCase(Locale.ROOT)
                .replace("iphone", " ")
                .replace("ไอโฟน", " ")
                .replace("promax", "pro max")
                // เติมช่องว่างให้ "15pro" → "15 pro" (ระวังอย่าแตะ 16e ที่ต้องติดกัน)
                .replaceAll("(?<=\\d)(pro|plus|mini|air)", " $1")
                .replaceAll("\\s+", " ")
                .trim();
    }


    // ==========================================
    // 🔢 Helper: อ่าน "จำนวนงวด" ที่ลูกค้าพิมพ์มา
    //
    // เดิมใช้ msg.matches(".*(6|8|10|12|หก|แปด|สิบ).*") ซึ่งพังหลายทาง:
    //   - "16" มีเลข 6 อยู่ข้างใน → ถูกนับเป็นเลือก 6 งวด
    //   - งวดใหม่ 15/18/21/24 ไม่ถูกรับเลย
    //   - รับทุกเลขโดยไม่สนว่ารุ่นนั้นเปิดให้ผ่อนกี่งวด (13 mini ตอบ 24 ก็ผ่าน)
    // ของใหม่: ตัดเป็นก้อนตัวเลขเต็มๆ แล้วเทียบกับงวดที่ "รุ่นนี้" มีจริงเท่านั้น
    // ==========================================
    private Integer parseSelectedMonth(String message, BalloonPrice price) {
        if (message == null || price == null) return null;
        String normalized = normalizeThaiDigits(message);

        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        while (matcher.find()) {
            try {
                int months = Integer.parseInt(matcher.group());
                if (price.supportsMonth(months)) return months;
            } catch (NumberFormatException ignored) {
                // ตัวเลขยาวเกิน int → ไม่ใช่จำนวนงวดแน่นอน ข้ามไป
            }
        }

        // คำไทย: เจอคำแรก (คำยาวสุด) แล้วจบ — ถ้ารุ่นนี้ไม่มีงวดนั้นให้ตอบว่าเลือกไม่ได้ไปเลย
        // อย่าไล่ต่อ ไม่งั้นลูกค้าขอ "สิบห้า" ในรุ่นที่ไม่มี 15 งวด จะกลายเป็นถูกลดให้เหลือ "สิบ" = 10 งวด
        for (Map.Entry<String, Integer> entry : THAI_MONTH_WORDS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return price.supportsMonth(entry.getValue()) ? entry.getValue() : null;
            }
        }
        return null;
    }

    /** "6, 8, 10, 12, 15, 18, 21 หรือ 24" — ไล่เฉพาะงวดที่รุ่นนั้นเปิดให้เลือก */
    private String formatMonthChoices(BalloonPrice price) {
        List<Integer> months = new ArrayList<>(price.monthlyPayments().keySet());
        if (months.size() == 1) return String.valueOf(months.get(0));
        String allButLast = months.subList(0, months.size() - 1).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return allButLast + " หรือ " + months.get(months.size() - 1);
    }

    private String formatBaht(int amount) {
        return String.format("%,d", amount);
    }

    private String normalizeThaiDigits(String value) {
        return value.replace('๐', '0').replace('๑', '1').replace('๒', '2').replace('๓', '3').replace('๔', '4')
                .replace('๕', '5').replace('๖', '6').replace('๗', '7').replace('๘', '8').replace('๙', '9');
    }

    private static Map<String, Integer> buildThaiMonthWords() {
        LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
        words.put("ยี่สิบสี่", 24);
        words.put("ยี่สิบเอ็ด", 21);
        words.put("สิบแปด", 18);
        words.put("สิบห้า", 15);
        words.put("สิบสอง", 12);
        words.put("สิบ", 10);
        words.put("แปด", 8);
        words.put("หก", 6);
        return Collections.unmodifiableMap(words);
    }

    private boolean isBusinessHours() {
        LocalTime now = LocalTime.now(BANGKOK_ZONE);
        return !now.isBefore(SHOP_OPEN_TIME) && !now.isAfter(SHOP_CLOSE_TIME);
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
