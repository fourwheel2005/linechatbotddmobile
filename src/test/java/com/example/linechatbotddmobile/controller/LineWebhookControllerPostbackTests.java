package com.example.linechatbotddmobile.controller;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import com.example.linechatbotddmobile.service.line.ChatFlowManager;
import com.example.linechatbotddmobile.service.line.LineMessageService;
import com.example.linechatbotddmobile.service.line.LineProfileService;
import com.example.linechatbotddmobile.service.line.UserConversationLockService;
import com.example.linechatbotddmobile.service.line.UserStateService;
import com.example.linechatbotddmobile.service.line.WebhookIdempotencyService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineWebhookControllerPostbackTests {

    private static final String USER_ID = "U-postback-test";

    private UserStateRepository repository;
    private UserStateService userStateService;
    private LineWebhookController controller;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(UserStateRepository.class);
        userStateService = Mockito.mock(UserStateService.class);
        controller = new LineWebhookController(
                Mockito.mock(ChatFlowManager.class),
                Mockito.mock(MessagingApiClient.class),
                repository,
                Mockito.mock(LineMessageService.class),
                Mockito.mock(LineProfileService.class),
                Mockito.mock(WebhookIdempotencyService.class),
                userStateService,
                new UserConversationLockService());
    }

    @AfterEach
    void tearDown() {
        controller.shutdownExecutors();
    }

    @Test
    void takeCasePausesBotAndRemembersTheCurrentStep() {
        UserState state = state("STEP_6_FACEID", null, "ผ่อนบอลลูน");
        when(userStateService.loadOrCreate(USER_ID)).thenReturn(state);

        LineWebhookController.PostbackResult result =
                controller.processPostbackAction("take_case", USER_ID, "ลูกค้าทดสอบ");

        assertThat(state.getCurrentState()).isEqualTo("ADMIN_MODE");
        assertThat(state.getPreviousState()).isEqualTo("STEP_6_FACEID");
        assertThat(result.messageToCustomer()).contains("แอดมินมารับเรื่องแล้ว");
        verify(repository).save(state);
    }

    @Test
    void resumeBotReturnsToRememberedStepWithoutChangingTheService() {
        UserState state = state("ADMIN_MODE", "STEP_6_FACEID", "ผ่อนบอลลูน");
        when(userStateService.loadOrCreate(USER_ID)).thenReturn(state);

        LineWebhookController.PostbackResult result =
                controller.processPostbackAction("resume_bot", USER_ID, "ลูกค้าทดสอบ");

        assertThat(state.getCurrentState()).isEqualTo("STEP_6_FACEID");
        assertThat(state.getPreviousState()).isNull();
        assertThat(state.getServiceName()).isEqualTo("ผ่อนบอลลูน");
        assertThat(result.messageToCustomer()).contains("กลับมาดูแลต่อแล้ว");
        verify(repository).save(state);
    }

    @Test
    void resumeBotRepairsMissingServiceAndUsesSafeStartWithoutPreviousStep() {
        UserState state = state("ADMIN_MODE", null, null);
        when(userStateService.loadOrCreate(USER_ID)).thenReturn(state);

        controller.processPostbackAction("resume_bot", USER_ID, "ลูกค้าทดสอบ");

        assertThat(state.getCurrentState()).isEqualTo("STEP_1_INFO");
        assertThat(state.getServiceName()).isEqualTo("ผ่อนบอลลูน");
    }

    private static UserState state(String currentState, String previousState, String serviceName) {
        UserState state = new UserState();
        state.setLineUserId(USER_ID);
        state.setCurrentState(currentState);
        state.setPreviousState(previousState);
        state.setServiceName(serviceName);
        return state;
    }
}
