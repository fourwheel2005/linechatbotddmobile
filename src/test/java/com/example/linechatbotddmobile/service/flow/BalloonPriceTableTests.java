package com.example.linechatbotddmobile.service.flow;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiDataExtractorService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService;
import com.example.linechatbotddmobile.service.line.LineMessageService;
import com.example.linechatbotddmobile.service.line.LineProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ล็อกตารางราคาผ่อนบอลลูนให้ตรงกับตารางของร้านแบบตัวต่อตัว
 *
 * ตัวเลขในคลาสนี้คัดมาจาก iphone_balloon_installments.csv ที่ร้านส่งมา (13 mini → 17 Pro Max, 22 รุ่น)
 * ถ้าใครแก้ราคาในโค้ดผิดตัวเดียว เทสต์นี้จะจับได้ทันทีว่ารุ่นไหน งวดไหน
 *
 * รูปแบบ: { รับซื้อ, 6, 8, 10, 12, 15, 18, 21, 24 } — ใส่เท่าที่รุ่นนั้นมีในตาราง
 */
class BalloonPriceTableTests {

    private static final List<Integer> ALL_MONTHS = List.of(6, 8, 10, 12, 15, 18, 21, 24);

    private static final Map<String, int[]> SHOP_PRICE_TABLE = new LinkedHashMap<>();

    static {
        // ตัวเลขทั้งบล็อกนี้ generate จาก iphone_balloon_installments.csv โดยตรง — ห้ามพิมพ์มือ
        SHOP_PRICE_TABLE.put("13 mini",    new int[]{3000,  1190,  890,  790,  690});
        SHOP_PRICE_TABLE.put("13",         new int[]{4000,  1290, 1090,  890,  790});
        SHOP_PRICE_TABLE.put("13 Pro",     new int[]{5000,  1590, 1290, 1090,  990});
        SHOP_PRICE_TABLE.put("13 Pro Max", new int[]{7000,  2290, 1790, 1590, 1390, 1090});
        SHOP_PRICE_TABLE.put("14",         new int[]{6000,  1990, 1590, 1390, 1190,  990});
        SHOP_PRICE_TABLE.put("14 Plus",    new int[]{7000,  2290, 1790, 1590, 1490, 1390, 1290});
        SHOP_PRICE_TABLE.put("14 Pro",     new int[]{7000,  2290, 1790, 1590, 1490, 1390, 1290});
        SHOP_PRICE_TABLE.put("14 Pro Max", new int[]{10000, 3190, 2590, 2190, 1990, 1590, 1390});
        SHOP_PRICE_TABLE.put("15",         new int[]{8000,  2550, 2050, 1750, 1550, 1250, 1050});
        SHOP_PRICE_TABLE.put("15 Plus",    new int[]{10000, 3190, 2590, 2190, 1990, 1590, 1390});
        SHOP_PRICE_TABLE.put("15 Pro",     new int[]{12000, 3850, 3050, 2550, 2350, 1950, 1650});
        SHOP_PRICE_TABLE.put("15 Pro Max", new int[]{13000, 4190, 3290, 2790, 2490, 2090, 1790});
        SHOP_PRICE_TABLE.put("16",         new int[]{9000,  2890, 2290, 1990, 1790, 1490, 1290});
        SHOP_PRICE_TABLE.put("16e",        new int[]{7000,  2290, 1790, 1590, 1490, 1390, 1290});
        SHOP_PRICE_TABLE.put("16 Plus",    new int[]{11000, 3550, 2750, 2350, 2150, 1750, 1550});
        SHOP_PRICE_TABLE.put("16 Pro",     new int[]{13000, 4190, 3290, 2790, 2490, 2090, 1790});
        SHOP_PRICE_TABLE.put("16 Pro Max", new int[]{15000, 4790, 3790, 3290, 2890, 2390, 2090, 1890, 1690});
        SHOP_PRICE_TABLE.put("17",         new int[]{13000, 4190, 3290, 2790, 2490, 2090, 1790});
        SHOP_PRICE_TABLE.put("17e",        new int[]{7000,  2290, 1790, 1590, 1490, 1390, 1290});
        SHOP_PRICE_TABLE.put("17 Air",     new int[]{12000, 3850, 3050, 2550, 2350, 1950, 1650});
        SHOP_PRICE_TABLE.put("17 Pro",     new int[]{15000, 4790, 3790, 3290, 2890, 2390, 2090, 1890, 1690});
        SHOP_PRICE_TABLE.put("17 Pro Max", new int[]{21000, 6950, 5550, 4650, 4050, 3350, 2950, 2550, 2350});
    }

    private UserStateRepository userStateRepository;
    private LineMessageService lineMessageService;
    private LineProfileService lineProfileService;
    private BalloonFlowService service;

    @BeforeEach
    void setUp() {
        userStateRepository = Mockito.mock(UserStateRepository.class);
        lineMessageService = Mockito.mock(LineMessageService.class);
        lineProfileService = Mockito.mock(LineProfileService.class);
        when(lineProfileService.getDisplayName(anyString())).thenReturn("ลูกค้าทดสอบ");

        service = new BalloonFlowService(
                userStateRepository,
                lineMessageService,
                Mockito.mock(AiDataExtractorService.class),
                Mockito.mock(AiScreeningService.class),
                lineProfileService
        );
    }

    // ══════════════════════════════════════════════════════════
    // ตารางราคา
    // ══════════════════════════════════════════════════════════

    @Test
    void everyModelQuotesExactlyTheShopPriceTable() {
        SHOP_PRICE_TABLE.forEach((model, row) -> {
            String quote = quoteFor(model);

            assertThat(quote)
                    .as("ยอดรับซื้อของ %s", model)
                    .contains("ยอดรับซื้อ: " + baht(row[0]) + " บ.");

            for (int i = 1; i < row.length; i++) {
                int months = ALL_MONTHS.get(i - 1);
                assertThat(quote)
                        .as("%s งวด %d เดือน", model, months)
                        .contains("- " + months + " เดือน: งวดละ " + baht(row[i]) + " บ.");
            }

            // งวดที่ตารางเว้นว่างไว้ ต้องไม่ถูกเสนอให้ลูกค้า
            for (int i = row.length - 1; i < ALL_MONTHS.size(); i++) {
                int unavailableMonths = ALL_MONTHS.get(i);
                assertThat(quote)
                        .as("%s ไม่ควรเสนองวด %d เดือน", model, unavailableMonths)
                        .doesNotContain("- " + unavailableMonths + " เดือน:");
            }
        });
    }

    @Test
    void smallModelsOnlyOfferShortTenors() {
        assertThat(quoteFor("13 mini"))
                .contains("- 12 เดือน: งวดละ 690 บ.")
                .doesNotContain("15 เดือน")
                .contains("(พิมพ์ตัวเลข 6, 8, 10 หรือ 12 ได้เลยครับ)");
    }

    @Test
    void flagshipModelsOfferAllEightTenors() {
        assertThat(quoteFor("17 Pro Max"))
                .contains("- 24 เดือน: งวดละ 2,350 บ.")
                .contains("(พิมพ์ตัวเลข 6, 8, 10, 12, 15, 18, 21 หรือ 24 ได้เลยครับ)");
    }

    @Test
    void modelNameVariantsStillResolveToTheSamePrice() {
        // AI/ลูกค้าส่งชื่อรุ่นมาได้หลายแบบ ต้องเจอราคาเดียวกันหมด
        assertThat(quoteFor("iPhone 15 Pro")).contains("ยอดรับซื้อ: 12,000 บ.");
        assertThat(quoteFor("15 promax")).contains("ยอดรับซื้อ: 13,000 บ.");
        assertThat(quoteFor("16E")).contains("ยอดรับซื้อ: 7,000 บ.");
        assertThat(quoteFor("  17   Pro  ")).contains("ยอดรับซื้อ: 15,000 บ.");
        assertThat(quoteFor("iphone15promax")).contains("ยอดรับซื้อ: 13,000 บ."); // พิมพ์ติดกันหมด
        assertThat(quoteFor("16e")).contains("ยอดรับซื้อ: 7,000 บ.");            // 16e ต้องไม่ถูกแยกเป็น "16 e"
        assertThat(quoteFor("17E")).contains("ยอดรับซื้อ: 7,000 บ.");            // รุ่นใหม่ในตาราง
    }

    @Test
    void thaiWordForATenorTheModelLacksIsNotSilentlyDowngraded() {
        // 13 mini ไม่มีงวด 15 เดือน — ห้ามตีความ "สิบห้า" เป็น "สิบ" (10 งวด) แล้วปิดการขายเลย
        UserState user = monthSelectionUser("13 mini");

        String reply = service.processMessage(user, "ขอสิบห้าเดือนครับ");

        assertThat(user.getCurrentState()).isEqualTo("STEP_6_MONTH_SELECTION");
        assertThat(reply).contains("6, 8, 10 หรือ 12");
        verify(lineMessageService, never()).sendSuccessCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void midRangeModelsStopAtEighteenMonths() {
        // งวด 21/24 เปิดให้เฉพาะ 16 Pro Max / 17 Pro / 17 Pro Max เท่านั้น
        assertThat(quoteFor("17"))
                .contains("- 18 เดือน: งวดละ 1,790 บ.")
                .doesNotContain("21 เดือน")
                .doesNotContain("24 เดือน")
                .contains("(พิมพ์ตัวเลข 6, 8, 10, 12, 15 หรือ 18 ได้เลยครับ)");
    }

    @Test
    void modelOutsideThePriceTableIsHandedToAdminInsteadOfGuessing() {
        // 12 Pro Max ถูกถอดออกจากตารางแล้ว (ร้านรับ 13 ขึ้นไป) — บอทต้องไม่เดาราคาเอง
        UserState user = pricingUser("12 Pro Max");

        String reply = service.processMessage(user, "continue");

        assertThat(user.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(reply).contains("แอดมิน");
        assertThat(reply).doesNotContain("งวดละ");
        verify(lineMessageService).sendEmergencyCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ══════════════════════════════════════════════════════════
    // การเลือกจำนวนงวด
    // ══════════════════════════════════════════════════════════

    @Test
    void customerCanPickAnyTenorTheModelOffers() {
        UserState user = monthSelectionUser("17 Pro Max");

        String reply = service.processMessage(user, "24 เดือนครับ");

        assertThat(user.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(reply).isNotBlank();
        verify(lineMessageService).sendSuccessCard(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("24 เดือน (งวดละ 2,350 บ.)"));
    }

    @Test
    void tenorThatTheModelDoesNotOfferIsRejected() {
        // 13 mini มีแค่ 6-12 งวด → ขอ 24 งวดไม่ได้
        UserState user = monthSelectionUser("13 mini");

        String reply = service.processMessage(user, "24");

        assertThat(user.getCurrentState()).isEqualTo("STEP_6_MONTH_SELECTION");
        assertThat(user.getRetryCount()).isEqualTo(1);
        assertThat(reply).contains("6, 8, 10 หรือ 12");
        verify(lineMessageService, never()).sendSuccessCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void numberThatMerelyContainsAValidTenorIsNotAccepted() {
        // ของเดิม ".*(6|8|10|12).*" ทำให้ "16" ถูกนับเป็นเลือก 6 งวด
        UserState user = monthSelectionUser("17 Pro Max");

        String reply = service.processMessage(user, "16");

        assertThat(user.getCurrentState()).isEqualTo("STEP_6_MONTH_SELECTION");
        assertThat(reply).contains("6, 8, 10, 12, 15, 18, 21 หรือ 24");
    }

    @Test
    void thaiWordsForTenorsAreUnderstood() {
        UserState user = monthSelectionUser("16 Pro");

        service.processMessage(user, "ขอสิบแปดเดือนครับ");

        assertThat(user.getCurrentState()).isEqualTo("ADMIN_MODE");
        verify(lineMessageService).sendSuccessCard(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("18 เดือน (งวดละ 1,790 บ.)"));
    }

    // --- helpers ---

    private String quoteFor(String model) {
        return service.processMessage(pricingUser(model), "continue");
    }

    private UserState pricingUser(String model) {
        UserState user = new UserState();
        user.setLineUserId("U-price-" + model);
        user.setCurrentState("STEP_5_PRICING");
        user.setDeviceModel(model);
        user.setCapacity("256GB");
        user.setRetryCount(0);
        return user;
    }

    private UserState monthSelectionUser(String model) {
        UserState user = pricingUser(model);
        user.setCurrentState("STEP_6_MONTH_SELECTION");
        return user;
    }

    private static String baht(int amount) {
        return String.format("%,d", amount);
    }
}
