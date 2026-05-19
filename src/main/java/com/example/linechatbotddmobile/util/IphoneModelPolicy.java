package com.example.linechatbotddmobile.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class IphoneModelPolicy {

    public static final String UNSUPPORTED_BELOW_IPHONE_12_MESSAGE =
            "ขออภัยครับลูกค้า ทางร้านเปิดรับ 12-17promax ครับ หากลูกค้ามีไอโฟน 12 ขึ้นติดต่อมาอีกครั้งนะครับผม";

    private static final Pattern UNSUPPORTED_MODEL_WITH_PHONE_CONTEXT = Pattern.compile(
            ".*(?:iphone|ไอโฟน|ไอ|ip)\\s*(?:se(?:\\s*(?:1|2|3|2020|2022))?|xs\\s*max|xs|xr|x|11(?:\\s*(?:pro\\s*max|promax|pro|pm))?|10|[1-9])(?!\\d).*"
    );

    private static final Pattern UNSUPPORTED_EXTRACTED_MODEL = Pattern.compile(
            "^(?:se(?:\\s*(?:1|2|3|2020|2022))?|xs\\s*max|xs|xr|x|11(?:\\s*(?:pro\\s*max|promax|pro|pm))?|10|[1-9])(?:\\b|\\s.*|$)"
    );

    private IphoneModelPolicy() {
    }

    public static boolean isUnsupportedBelowIphone12Message(String message) {
        if (message == null || message.isBlank()) return false;
        return UNSUPPORTED_MODEL_WITH_PHONE_CONTEXT.matcher(normalize(message)).matches();
    }

    public static boolean isUnsupportedBelowIphone12Model(String modelName) {
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
                .replace('๐', '0')
                .replaceAll("[._\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
