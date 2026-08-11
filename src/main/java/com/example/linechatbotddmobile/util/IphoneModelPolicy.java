package com.example.linechatbotddmobile.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * เกณฑ์รุ่นเครื่องที่ทางร้านรับซื้อ/รับผ่อน — ปัจจุบันรับตั้งแต่ iPhone 13 mini ขึ้นไป
 *
 * เดิมรับ 12 ขึ้นไป แต่ตารางราคาชุดใหม่ (iphone_balloon_installments.csv) ตัดรุ่น 12 ออกทั้งหมด
 * จึงต้องคัดออกตั้งแต่ต้นทาง ลูกค้าจะได้ไม่ตอบคำถามคัดกรองจนจบแล้วค่อยมารู้ว่าไม่มีราคาให้
 */
public final class IphoneModelPolicy {

    public static final String UNSUPPORTED_BELOW_IPHONE_13_MESSAGE =
            "ขออภัยครับลูกค้า ทางร้านเปิดรับ 13-17promax ครับ หากลูกค้ามีไอโฟน 13 ขึ้นติดต่อมาอีกครั้งนะครับผม";

    /**
     * รุ่นที่ไม่รับ: SE ทุกรุ่น, X / XR / XS / XS Max, 10, 11 และ 12 (รวมรุ่นย่อย mini / Pro / Pro Max)
     * ตัว [1-9] ท้ายสุดไว้จับรุ่นเลขหลักเดียว (iPhone 5-9) โดยมี (?!\\d) กัน 13-17 ไม่ให้ติดร่างแห
     */
    private static final String UNSUPPORTED_MODEL_ALTERNATIVES =
            "se(?:\\s*(?:1|2|3|2020|2022))?"
                    + "|xs\\s*max|xs|xr|x"
                    + "|1[12](?:\\s*(?:pro\\s*max|promax|pro|pm|mini|plus))?"
                    + "|10|[1-9]";

    private static final Pattern UNSUPPORTED_MODEL_WITH_PHONE_CONTEXT = Pattern.compile(
            ".*(?:iphone|ไอโฟน|ไอ|ip)\\s*(?:" + UNSUPPORTED_MODEL_ALTERNATIVES + ")(?!\\d).*"
    );

    private static final Pattern UNSUPPORTED_EXTRACTED_MODEL = Pattern.compile(
            "^(?:" + UNSUPPORTED_MODEL_ALTERNATIVES + ")(?:\\b|\\s.*|$)"
    );

    private IphoneModelPolicy() {
    }

    public static boolean isUnsupportedBelowIphone13Message(String message) {
        if (message == null || message.isBlank()) return false;
        return UNSUPPORTED_MODEL_WITH_PHONE_CONTEXT.matcher(normalize(message)).matches();
    }

    public static boolean isUnsupportedBelowIphone13Model(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        String normalizedModel = normalize(modelName)
                .replace("iphone", "")
                .replace("ไอโฟน", "")
                .replace("ip", "")
                .replace("ไอ", "")
                .trim();
        return UNSUPPORTED_EXTRACTED_MODEL.matcher(normalizedModel).matches();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('๑', '1')
                .replace('๒', '2')
                .replace('๓', '3')
                .replace('๔', '4')
                .replace('๕', '5')
                .replace('๖', '6')
                .replace('๗', '7')
                .replace('๘', '8')
                .replace('๙', '9')
                .replaceAll("[._\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
