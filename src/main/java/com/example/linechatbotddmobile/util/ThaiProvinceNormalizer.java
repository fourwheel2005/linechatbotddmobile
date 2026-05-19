package com.example.linechatbotddmobile.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ThaiProvinceNormalizer {

    private static final Map<String, String> PROVINCES = new LinkedHashMap<>();

    static {
        register("กรุงเทพมหานคร", "กรุงเทพ", "กทม", "กทม.");
        register("กระบี่");
        register("กาญจนบุรี");
        register("กาฬสินธุ์", "กาฬสินธุ");
        register("กำแพงเพชร");
        register("ขอนแก่น");
        register("จันทบุรี");
        register("ฉะเชิงเทรา");
        register("ชลบุรี");
        register("ชัยนาท");
        register("ชัยภูมิ");
        register("ชุมพร");
        register("เชียงราย");
        register("เชียงใหม่");
        register("ตรัง");
        register("ตราด");
        register("ตาก");
        register("นครนายก");
        register("นครปฐม");
        register("นครพนม");
        register("นครราชสีมา", "โคราช");
        register("นครศรีธรรมราช", "นครศรี");
        register("นครสวรรค์");
        register("นนทบุรี");
        register("นราธิวาส");
        register("น่าน");
        register("บึงกาฬ");
        register("บุรีรัมย์", "บุรีรัม");
        register("ปทุมธานี");
        register("ประจวบคีรีขันธ์", "ประจวบ");
        register("ปราจีนบุรี");
        register("ปัตตานี");
        register("พระนครศรีอยุธยา", "อยุธยา");
        register("พะเยา");
        register("พังงา");
        register("พัทลุง");
        register("พิจิตร");
        register("พิษณุโลก");
        register("เพชรบุรี");
        register("เพชรบูรณ์");
        register("แพร่");
        register("ภูเก็ต");
        register("มหาสารคาม");
        register("มุกดาหาร");
        register("แม่ฮ่องสอน");
        register("ยโสธร");
        register("ยะลา");
        register("ร้อยเอ็ด");
        register("ระนอง");
        register("ระยอง");
        register("ราชบุรี");
        register("ลพบุรี");
        register("ลำปาง");
        register("ลำพูน");
        register("เลย");
        register("ศรีสะเกษ", "ศรีษะเกษ", "ศรีสเกษ");
        register("สกลนคร");
        register("สงขลา");
        register("สตูล");
        register("สมุทรปราการ");
        register("สมุทรสงคราม");
        register("สมุทรสาคร");
        register("สระแก้ว");
        register("สระบุรี");
        register("สิงห์บุรี", "สิงบุรี");
        register("สุโขทัย");
        register("สุพรรณบุรี");
        register("สุราษฎร์ธานี", "สุราษ", "สุราษฎร์");
        register("สุรินทร์", "สุริน");
        register("หนองคาย");
        register("หนองบัวลำภู");
        register("อ่างทอง");
        register("อำนาจเจริญ");
        register("อุดรธานี", "อุดร");
        register("อุตรดิตถ์");
        register("อุทัยธานี");
        register("อุบลราชธานี", "อุบล");
    }

    private ThaiProvinceNormalizer() {}

    public static Optional<String> normalize(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalizeText(message);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        String directMatch = PROVINCES.get(normalized);
        if (directMatch != null) {
            return Optional.of(directMatch);
        }

        for (Map.Entry<String, String> entry : PROVINCES.entrySet()) {
            if (normalized.equals("จังหวัด" + entry.getKey()) || normalized.equals("จ" + entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }

        return Optional.empty();
    }

    private static void register(String canonical, String... aliases) {
        PROVINCES.put(normalizeText(canonical), canonical);
        for (String alias : aliases) {
            PROVINCES.put(normalizeText(alias), canonical);
        }
    }

    private static String normalizeText(String text) {
        return text.toLowerCase()
                .replaceAll("[\\s\\p{Punct}]+", "")
                .replaceAll("^(อยู่ที่|อยู่จังหวัด|อยู่|จังหวัด)", "")
                .replaceAll("(นะครับ|นะคะ|ครับผม|ค่ะ|คะ|ครับ|ค้าบ|จ้า|จ่ะ|ฮะ)$", "")
                .trim();
    }
}
