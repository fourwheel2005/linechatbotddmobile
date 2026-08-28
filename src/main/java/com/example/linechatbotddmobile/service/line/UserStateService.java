package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Resolves one durable state row per LINE user, including cross-instance create races. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStateService {

    private final UserStateRepository repository;

    public UserState loadOrCreate(String lineUserId) {
        return repository.findByLineUserId(lineUserId)
                .orElseGet(() -> createOrLoadConcurrentWinner(lineUserId));
    }

    private UserState createOrLoadConcurrentWinner(String lineUserId) {
        UserState newState = new UserState();
        newState.setLineUserId(lineUserId);
        try {
            return repository.saveAndFlush(newState);
        } catch (DataIntegrityViolationException raceException) {
            log.info("Concurrent UserState creation resolved by the unique line-user constraint");
            return repository.findByLineUserId(lineUserId)
                    .orElseThrow(() -> raceException);
        }
    }
}
