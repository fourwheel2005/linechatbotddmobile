package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerFollowUpReminderSchedulerTests {

    private static final String USER_ID = "U-reminder-test";

    private UserStateRepository repository;
    private LineMessageService messageService;
    private CustomerFollowUpReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(UserStateRepository.class);
        messageService = Mockito.mock(LineMessageService.class);
        scheduler = new CustomerFollowUpReminderScheduler(
                repository,
                messageService,
                new UserConversationLockService());
    }

    @Test
    void rechecksStateInsideUserLockBeforeSendingReminder() {
        UserState staleCandidate = dueState("STEP_10_NAME");
        UserState customerAlreadyContinued = dueState("STEP_11_SUBMIT_DATA");
        when(repository.findDueFollowUpReminders(any(), any())).thenReturn(List.of(staleCandidate));
        when(repository.findByLineUserId(USER_ID)).thenReturn(Optional.of(customerAlreadyContinued));

        scheduler.sendFollowUpReminderToInactiveCustomers();

        verify(messageService, never()).trySendTextMessage(anyString(), anyString());
        verify(repository, never()).save(any(UserState.class));
    }

    @Test
    void sendsAndMarksReminderWhenStateIsStillDue() {
        UserState dueState = dueState("STEP_10_NAME");
        when(repository.findDueFollowUpReminders(any(), any())).thenReturn(List.of(dueState));
        when(repository.findByLineUserId(USER_ID)).thenReturn(Optional.of(dueState));
        when(messageService.trySendTextMessage(anyString(), anyString())).thenReturn(true);

        scheduler.sendFollowUpReminderToInactiveCustomers();

        verify(messageService).trySendTextMessage(USER_ID,
                "ลูกค้าติดตรงไหน ไม่เข้าใจตรงไหนถามแอดมินได้เลยนะครับ");
        verify(repository).save(dueState);
    }

    private static UserState dueState(String currentState) {
        UserState state = new UserState();
        state.setLineUserId(USER_ID);
        state.setCurrentState(currentState);
        state.setFollowUpReminderStartedAt(LocalDateTime.now().minusHours(1));
        state.setFollowUpReminderSent(false);
        return state;
    }
}
