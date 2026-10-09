package com.example.linechatbotddmobile.service.flow;

import com.example.linechatbotddmobile.dto.ExtractedData;
import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiDataExtractorService;
import com.example.linechatbotddmobile.service.ai.AiScreeningService;
import com.example.linechatbotddmobile.service.line.LineMessageService;
import com.example.linechatbotddmobile.service.line.LineProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BalloonFlowService} focusing on safe, high-confidence assertions:
 *   - Service identity (supports / getServiceName)
 *   - Panic-keyword detection that escalates to ADMIN_MODE
 *   - First-touch transition from STEP_1_INFO to STEP_2_CAPACITY
 *   - Retry counter behavior under invalid input
 *
 * Heavy AI-dependent state transitions are out of scope here — they belong in a
 * separate integration test with WireMock for the OpenAI endpoint.
 */
class BalloonFlowServiceTests {

    private UserStateRepository userStateRepository;
    private LineMessageService lineMessageService;
    private AiDataExtractorService aiDataExtractorService;
    private AiScreeningService aiScreeningService;
    private LineProfileService lineProfileService;
    private BalloonFlowService service;

    @BeforeEach
    void setUp() {
        userStateRepository = Mockito.mock(UserStateRepository.class);
        lineMessageService = Mockito.mock(LineMessageService.class);
        aiDataExtractorService = Mockito.mock(AiDataExtractorService.class);
        aiScreeningService = Mockito.mock(AiScreeningService.class);
        lineProfileService = Mockito.mock(LineProfileService.class);
        when(lineProfileService.getDisplayName(anyString())).thenReturn("ลูกค้าทดสอบ");

        service = new BalloonFlowService(
                userStateRepository,
                lineMessageService,
                aiDataExtractorService,
                aiScreeningService,
                lineProfileService
        );
    }

    @Test
    void supportsOnlyBalloonService() {
        assertThat(service.supports("ผ่อนบอลลูน")).isTrue();
        assertThat(service.supports("ผ่อนบัตร")).isFalse();
        assertThat(service.supports("")).isFalse();
        assertThat(service.supports(null)).isFalse();
    }

    @Test
    void serviceNameIsStable() {
        // ห้ามเปลี่ยน — ใช้เป็นคีย์ภายนอกอ้างอิงในระบบ admin card
        assertThat(service.getServiceName()).isEqualTo("ผ่อนบอลลูน");
    }

    @Test
    void step1InfoTransitionsToStep2Capacity() {
        UserState user = newUser("U-1", "STEP_1_INFO");

        String reply = service.processMessage(user, "สวัสดีครับ");

        assertThat(user.getCurrentState()).isEqualTo("STEP_2_CAPACITY");
        assertThat(reply).contains("รุ่นไหน");
        // ขั้นนี้ไม่ต้องคุย AI / DB อะไรเพิ่ม
        verify(aiDataExtractorService, never()).extractInfo(anyString(), any());
    }

    @Test
    void panicKeywordEscalatesToAdminMode() {
        UserState user = newUser("U-panic", "STEP_2_CAPACITY");

        String reply = service.processMessage(user, "คุยกับคน");

        assertThat(user.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(user.getPreviousState()).isEqualTo("STEP_2_CAPACITY");
        verify(userStateRepository, times(1)).save(user);
        verify(lineMessageService, times(1))
                .sendEmergencyCard(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(reply).isNotBlank();
    }

    @Test
    void invalidIphoneModelIncrementsRetryButKeepsStep() {
        when(aiDataExtractorService.extractInfo(anyString(), any()))
                .thenReturn(new ExtractedData("unknown", 0, "unknown", "unknown"));

        UserState user = newUser("U-retry", "STEP_2_CAPACITY");
        user.setRetryCount(0);

        String reply = service.processMessage(user, "ไม่รู้สิ บอกไม่ถูก xyz");

        assertThat(user.getCurrentState()).isEqualTo("STEP_2_CAPACITY");
        assertThat(user.getRetryCount()).isEqualTo(1);
        assertThat(reply).isNotBlank();
    }

    @Test
    void modelIsGuessedFromRawMessageWhenAiExtractionFails() {
        // AI ล่ม/ตอบ unknown → ต้องเดารุ่นจากข้อความให้ครบทั้งรุ่นย่อย ไม่ใช่เหลือแต่เลขรุ่น
        // (ถ้าเหลือแค่ "13" ลูกค้าจะโดนเสนอราคา 4,000 แทน 7,000)
        when(aiDataExtractorService.extractInfo(anyString(), any()))
                .thenReturn(new ExtractedData("unknown", 0, "unknown", "unknown"));

        assertThat(modelFor("13 Pro Max")).isEqualTo("13 Pro Max");
        assertThat(modelFor("14 Plus")).isEqualTo("14 Plus");
        assertThat(modelFor("13 Mini")).isEqualTo("13 mini");
        assertThat(modelFor("17 Air")).isEqualTo("17 Air");
        assertThat(modelFor("16E")).isEqualTo("16e");
        assertThat(modelFor("15 PRO")).isEqualTo("15 Pro");
        assertThat(modelFor("16")).isEqualTo("16");
        // iPhone 18
        assertThat(modelFor("18")).isEqualTo("18");
        assertThat(modelFor("18 Pro")).isEqualTo("18 Pro");
        assertThat(modelFor("18 pm")).isEqualTo("18 Pro Max");
    }

    // ══════════════════════════════════════════════════════════
    // iPhone Duo — ชื่อรุ่นจริงไม่มีตัวเลข ต้องเก็บเป็น "Duo" ชื่อเดียวเสมอ
    // ══════════════════════════════════════════════════════════

    @Test
    void iphoneDuoIsRecognisedWithoutAModelNumberWhenAiFails() {
        aiReturnsModel("unknown");

        assertThat(modelFor("Duo")).isEqualTo("Duo");
        assertThat(modelFor("iPhone Duo")).isEqualTo("Duo");
        assertThat(modelFor("iphoneduo")).isEqualTo("Duo");
        assertThat(modelFor("ไอโฟนดูโอ")).isEqualTo("Duo");
        assertThat(modelFor("ดูโอ ครับ")).isEqualTo("Duo");
        assertThat(modelFor("18 Duo")).isEqualTo("Duo");   // ชื่อเก่าที่ลูกค้าอาจพิมพ์ ต้องรวมเป็นชื่อเดียว
    }

    @Test
    void iphoneDuoIsCanonicalisedWhateverTheAiReturns() {
        aiReturnsModel("18 Duo");                           // AI เลียนตัวอย่างเก่า
        assertThat(modelFor("iPhone Duo")).isEqualTo("Duo");

        aiReturnsModel("ดูโอ");                             // AI ส่งคำไทยกลับมา
        assertThat(modelFor("ไอโฟนดูโอ")).isEqualTo("Duo");

        aiReturnsModel("18");                               // AI ตัดคำว่า Duo ทิ้ง → ห้ามเสนอราคา iPhone 18
        assertThat(modelFor("iPhone 18 Duo")).isEqualTo("Duo");
    }

    @Test
    void iphoneDuoConfirmationKeepsTheExistingSentence() {
        aiReturnsModel("Duo");
        UserState user = newUser("U-duo", "STEP_2_CAPACITY");

        String reply = service.processMessage(user, "iPhone Duo");

        assertThat(reply).isEqualTo("รับทราบครับ รุ่น **iPhone Duo** นะครับ! 📱\n"
                + "👉 ความจุ **กี่ GB** ครับผม? (เช่น 128, 256, 512)");
        assertThat(user.getCurrentState()).isEqualTo("STEP_3_PROVINCE");
    }

    // ══════════════════════════════════════════════════════════
    // ชื่อรุ่นย่อยภาษาไทย + กัน AI ตัดรุ่นย่อยทิ้ง (เสี่ยงเสนอราคาต่ำกว่าจริง)
    // ══════════════════════════════════════════════════════════

    @Test
    void thaiSubModelWordsAreUnderstoodWhenAiFails() {
        aiReturnsModel("unknown");

        assertThat(modelFor("14 พลัส")).isEqualTo("14 Plus");
        assertThat(modelFor("13 โปรแม็กซ์")).isEqualTo("13 Pro Max");
        assertThat(modelFor("13 โปรแมก")).isEqualTo("13 Pro Max");
        assertThat(modelFor("15 โปร")).isEqualTo("15 Pro");
        assertThat(modelFor("13 มินิ")).isEqualTo("13 mini");
        assertThat(modelFor("17 แอร์")).isEqualTo("17 Air");
        assertThat(modelFor("ไอโฟน 14 plus")).isEqualTo("14 Plus");  // มีคำว่าไอโฟนนำหน้า
    }

    @Test
    void subModelTypedRightAfterTheNumberWinsOverABareNumberFromAi() {
        aiReturnsModel("14");
        assertThat(modelFor("14 พลัส")).isEqualTo("14 Plus");

        aiReturnsModel("15");
        assertThat(modelFor("15 Pro ครับ")).isEqualTo("15 Pro");

        aiReturnsModel("13");
        assertThat(modelFor("13pm")).isEqualTo("13 Pro Max");
    }

    @Test
    void aiAnswerIsKeptWhenTheMessageHasNoExplicitSubModel() {
        aiReturnsModel("15");
        assertThat(modelFor("15 มีโปรโมชั่นไหมครับ")).isEqualTo("15");   // "โปรโมชั่น" ไม่ใช่รุ่น Pro
        assertThat(modelFor("15 promotion")).isEqualTo("15");

        aiReturnsModel("16");
        assertThat(modelFor("16 ครับ")).isEqualTo("16");

        aiReturnsModel("15 Pro");                           // AI ตอบรุ่นย่อยมาแล้ว → เชื่อ AI ไม่ทับ
        assertThat(modelFor("15 pm")).isEqualTo("15 Pro");
    }

    private void aiReturnsModel(String model) {
        when(aiDataExtractorService.extractInfo(anyString(), any()))
                .thenReturn(new ExtractedData(model, 0, "unknown", "unknown"));
    }

    private String modelFor(String customerMessage) {
        UserState user = newUser("U-guess", "STEP_2_CAPACITY");
        service.processMessage(user, customerMessage);
        return user.getDeviceModel();
    }

    @Test
    void secondInvalidIphoneModelEscalatesToAdminMode() {
        when(aiDataExtractorService.extractInfo(anyString(), any()))
                .thenReturn(new ExtractedData("unknown", 0, "unknown", "unknown"));

        UserState user = newUser("U-retry2", "STEP_2_CAPACITY");
        user.setRetryCount(1); // ครั้งที่ 2

        String reply = service.processMessage(user, "ก็บอกแล้วไง");

        // ครั้งที่ 2 → MAX_RETRY_ATTEMPTS=2 → ADMIN_MODE
        assertThat(user.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(user.getRetryCount()).isZero();
        assertThat(reply).isNotBlank();
    }

    // --- helpers ---
    private static UserState newUser(String userId, String state) {
        UserState u = new UserState();
        u.setLineUserId(userId);
        u.setCurrentState(state);
        u.setRetryCount(0);
        return u;
    }
}
