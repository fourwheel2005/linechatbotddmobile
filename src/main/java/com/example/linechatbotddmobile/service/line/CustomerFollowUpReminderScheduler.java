package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerFollowUpReminderScheduler {

    private static final ZoneId BANGKOK_ZONE = ZoneId.of("Asia/Bangkok");
    private static final List<String> FOLLOW_UP_STATES = List.of(
            "STEP_9_SETTINGS_PHOTO",
            "STEP_10_NAME"
    );
    private static final String REMINDER_MESSAGE =
            "ลูกค้าติดตรงไหน ไม่เข้าใจตรงไหนถามแอดมินได้เลยนะครับ";

    private final UserStateRepository userStateRepository;
    private final LineMessageService lineMessageService;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sendFollowUpReminderToInactiveCustomers() {
        LocalDateTime cutoff = LocalDateTime.now(BANGKOK_ZONE).minusMinutes(10);
        List<UserState> dueStates = userStateRepository.findDueFollowUpReminders(FOLLOW_UP_STATES, cutoff);

        for (UserState userState : dueStates) {
            if (userState.getLineUserId() == null || userState.getLineUserId().isBlank()) {
                continue;
            }

            boolean sent = lineMessageService.trySendTextMessage(userState.getLineUserId(), REMINDER_MESSAGE);
            if (!sent) {
                continue;
            }

            userState.setFollowUpReminderSent(true);
            userStateRepository.save(userState);

            log.info("ส่งข้อความ follow-up หลังลูกค้าเงียบเกิน 10 นาที: userId={}, state={}",
                    userState.getLineUserId(),
                    userState.getCurrentState());
        }
    }
}
