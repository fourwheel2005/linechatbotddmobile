package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.ChatHistoryRepository;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.ai.AiChatService;
import com.example.linechatbotddmobile.service.flow.ServiceFlowHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * เทสต์การ "เลือกทาง" ของ {@link ChatFlowManager} — จุดที่ทำให้ลูกค้าหลุดออกจาก flow
 * แล้วไปโดน AI ตอบแทน ซึ่งเป็นอาการ "บอทตอบไม่เข้า flow"
 *
 * โฟกัสที่ 3 เรื่อง:
 *   - ลูกค้าที่มี state ค้างอยู่ ต้องกลับเข้า flow ได้เมื่อแสดงความสนใจ
 *   - คำตอบกลาง flow ต้องไม่ถูกคีย์เวิร์ด (จำนำ / ผ่อน) ดึงออกจาก flow
 *   - ปุ่มริชเมนู / ปุ่มการ์ดต้อนรับ ต้องพาเข้า flow เดิมได้
 */
class ChatFlowManagerTests {

    private static final String USER_ID = "U-test-1234";
    private static final String BALLOON_REPLY = "ข้อความจาก BalloonFlowService";

    private UserStateRepository userStateRepository;
    private ChatHistoryRepository chatHistoryRepository;
    private AiChatService aiChatService;
    private LineMessageService lineMessageService;
    private LineProfileService lineProfileService;
    private ServiceFlowHandler balloonHandler;
    private ChatFlowManager manager;

    @BeforeEach
    void setUp() {
        userStateRepository = Mockito.mock(UserStateRepository.class);
        chatHistoryRepository = Mockito.mock(ChatHistoryRepository.class);
        aiChatService = Mockito.mock(AiChatService.class);
        lineMessageService = Mockito.mock(LineMessageService.class);
        lineProfileService = Mockito.mock(LineProfileService.class);
        balloonHandler = Mockito.mock(ServiceFlowHandler.class);

        when(lineProfileService.getDisplayName(anyString())).thenReturn("ลูกค้าทดสอบ");
        when(balloonHandler.supports("ผ่อนบอลลูน")).thenReturn(true);
        when(balloonHandler.getServiceName()).thenReturn("ผ่อนบอลลูน");
        when(balloonHandler.processMessage(any(UserState.class), anyString())).thenReturn(BALLOON_REPLY);
        when(aiChatService.generateResponse(anyString(), anyString())).thenReturn("คำตอบจาก AI");

        manager = new ChatFlowManager(
                userStateRepository,
                List.of(balloonHandler),
                aiChatService,
                chatHistoryRepository,
                lineMessageService,
                lineProfileService
        );
    }

    private UserState givenState(String currentState, String serviceName) {
        UserState userState = new UserState();
        userState.setLineUserId(USER_ID);
        userState.setCurrentState(currentState);
        userState.setServiceName(serviceName);
        when(userStateRepository.findByLineUserId(USER_ID)).thenReturn(Optional.of(userState));
        return userState;
    }

    // ══════════════════════════════════════════════════════════
    // กลับเข้า flow ได้
    // ══════════════════════════════════════════════════════════

    @Test
    void welcomeCardButtonAlwaysRestartsBalloonFlow() {
        UserState userState = givenState("ADMIN_MODE", "จำนำ iCloud");

        String reply = manager.handleTextMessage(USER_ID, "สนใจผ่อนบอลลูน");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getCurrentState()).isEqualTo("STEP_1_INFO");
        assertThat(userState.getServiceName()).isEqualTo("ผ่อนบอลลูน");
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    @Test
    void customerTypingLooseKeywordEntersBalloonFlow() {
        // ลูกค้าพิมพ์เองว่า "สนใจบอลลูน" (ไม่ตรงข้อความปุ่ม) และมี state เก่าค้างอยู่
        UserState userState = givenState("REJECTED", "ผ่อนบอลลูน");

        String reply = manager.handleTextMessage(USER_ID, "สนใจบอลลูน");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getCurrentState()).isEqualTo("STEP_1_INFO");
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    @Test
    void customerWithIcloudServiceNameCanStillStartBalloonFlow() {
        // serviceName เป็น "จำนำ iCloud" ซึ่งไม่มี handler รองรับ → เดิมจะร่วงไปหา AI ตลอด
        UserState userState = givenState("REJECTED", "จำนำ iCloud");

        String reply = manager.handleTextMessage(USER_ID, "สนใจผ่อน");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getServiceName()).isEqualTo("ผ่อนบอลลูน");
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    @Test
    void orphanStepStateWithoutServiceNameIsRepairedAndDispatched() {
        // state ค้างที่ STEP_* แต่ serviceName หาย (เช่นหลังแอดมินกดคืนบอทจาก panic mode)
        UserState userState = givenState("STEP_5_REPAIR", null);

        String reply = manager.handleTextMessage(USER_ID, "25");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getServiceName()).isEqualTo("ผ่อนบอลลูน");
        assertThat(userState.getCurrentState()).isEqualTo("STEP_5_REPAIR"); // ไม่รีเซ็ตสเต็ปที่ค้างอยู่
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    @Test
    void brandNewCustomerShowingInterestEntersBalloonFlow() {
        when(userStateRepository.findByLineUserId(USER_ID)).thenReturn(Optional.empty());

        String reply = manager.handleTextMessage(USER_ID, "สนใจผ่อนไอโฟนครับ");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    // ══════════════════════════════════════════════════════════
    // ไม่ถูกดึงออกจาก flow กลางคัน
    // ══════════════════════════════════════════════════════════

    @Test
    void midFlowAnswerAboutPawnStaysInFlow() {
        // STEP_8 ถามว่า "ติดผ่อน / ติด iCloud ร้านอื่นไหม" → คำตอบต้องวิ่งเข้า flow ไม่ใช่เข้าโหมดจำนำ
        UserState userState = givenState("STEP_8_DEVICE_PHOTOS", "ผ่อนบอลลูน");

        String reply = manager.handleTextMessage(USER_ID, "ไม่ได้จำนำที่ไหนครับ");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getCurrentState()).isEqualTo("STEP_8_DEVICE_PHOTOS");
        verify(lineMessageService, never()).sendEmergencyCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void midFlowAnswerContainingInstalmentWordIsNotReset() {
        UserState userState = givenState("STEP_8_DEVICE_PHOTOS", "ผ่อนบอลลูน");

        String reply = manager.handleTextMessage(USER_ID, "ไม่ติดผ่อนครับ");

        assertThat(reply).isEqualTo(BALLOON_REPLY);
        assertThat(userState.getCurrentState()).isEqualTo("STEP_8_DEVICE_PHOTOS");
    }

    @Test
    void icloudButtonTapWorksEvenWhileBalloonFlowIsInProgress() {
        // กดปุ่มจากการ์ด = ตั้งใจเปลี่ยนบริการชัดเจน ต้องรับเสมอ
        UserState userState = givenState("STEP_8_DEVICE_PHOTOS", "ผ่อนบอลลูน");

        String reply = manager.handleTextMessage(USER_ID, "สนใจจำนำ iCloud");

        assertThat(reply).contains("จำนำ iCloud");
        assertThat(userState.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(userState.getPreviousState()).isEqualTo("STEP_8_DEVICE_PHOTOS"); // จำสเต็ปเดิมไว้ให้ปุ่มคืนบอท
        verify(lineMessageService).sendEmergencyCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void pawnRequestWhenIdleHandsOverToAdmin() {
        UserState userState = givenState(null, null);

        String reply = manager.handleTextMessage(USER_ID, "สนใจจำนำ iCloud");

        assertThat(reply).contains("จำนำ iCloud");
        assertThat(userState.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(userState.getServiceName()).isEqualTo("จำนำ iCloud");
        verify(lineMessageService).sendEmergencyCard(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ══════════════════════════════════════════════════════════
    // ริชเมนู / โหมดแอดมิน
    // ══════════════════════════════════════════════════════════

    @Test
    void richMenuTapSendsWelcomeCardWithoutTouchingFlowState() {
        UserState userState = givenState("STEP_4_AGE", "ผ่อนบอลลูน");

        String reply = manager.handleTextMessage(USER_ID, "ทันใจทันใช้");

        assertThat(reply).isNull(); // การ์ดถูก push ไปแล้ว ไม่ตอบข้อความซ้ำ
        verify(lineMessageService).sendWelcomeCard(USER_ID);
        assertThat(userState.getCurrentState()).isEqualTo("STEP_4_AGE"); // flow เดิมไม่ถูกรีเซ็ต
        verify(balloonHandler, never()).processMessage(any(UserState.class), anyString());
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }

    @Test
    void onlyExactRichMenuTextCountsAsWelcomeTrigger() {
        // ปุ่ม "คุยกับแอดมิน" (ช่อง F ของริชเมนู) ตั้งค่าให้ส่งข้อความ "ทันใจทันใช้" — ดักที่ข้อความนี้
        assertThat(ChatFlowManager.isWelcomeMenuTrigger("ทันใจทันใช้")).isTrue();
        assertThat(ChatFlowManager.isWelcomeMenuTrigger(" ทันใจทันใช้ ")).isTrue();
        // ลูกค้าพิมพ์ขอคุยกับคนเอง ต้องไม่ถูกกลืน ให้ไปเข้า panic mode ตามเดิม
        assertThat(ChatFlowManager.isWelcomeMenuTrigger("ขอคุยกับแอดมินหน่อยครับ")).isFalse();
        assertThat(ChatFlowManager.isWelcomeMenuTrigger("แอดมินครับ")).isFalse();
        assertThat(ChatFlowManager.isWelcomeMenuTrigger(null)).isFalse();
    }

    @Test
    void botStaysSilentWhileAdminIsHandlingTheCase() {
        givenState("ADMIN_MODE", "ผ่อนบอลลูน");

        assertThat(manager.handleTextMessage(USER_ID, "ทันใจทันใช้")).isNull();
        assertThat(manager.handleTextMessage(USER_ID, "สอบถามหน่อยครับ")).isNull();
        verify(lineMessageService, never()).sendWelcomeCard(anyString()); // ไม่เด้งการ์ดทับตอนแอดมินคุยอยู่
        verify(aiChatService, never()).generateResponse(anyString(), anyString());
    }
}
