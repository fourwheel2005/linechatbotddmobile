package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.UserState;
import com.example.linechatbotddmobile.repository.UserStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserStateServiceTests {

    private static final String USER_ID = "U-state-test";

    private UserStateRepository repository;
    private UserStateService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(UserStateRepository.class);
        service = new UserStateService(repository);
    }

    @Test
    void returnsExistingStateWithoutCreatingAnotherRow() {
        UserState existing = state(10L);
        when(repository.findByLineUserId(USER_ID)).thenReturn(Optional.of(existing));

        assertThat(service.loadOrCreate(USER_ID)).isSameAs(existing);
    }

    @Test
    void createsAndFlushesStateWhenUserIsNew() {
        UserState persisted = state(11L);
        when(repository.findByLineUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(UserState.class))).thenReturn(persisted);

        assertThat(service.loadOrCreate(USER_ID)).isSameAs(persisted);
        verify(repository).saveAndFlush(any(UserState.class));
    }

    @Test
    void recoversWhenAnotherInstanceCreatesTheSameUserFirst() {
        UserState winner = state(12L);
        when(repository.findByLineUserId(USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any(UserState.class)))
                .thenThrow(new DataIntegrityViolationException("unique line_user_id"));

        assertThat(service.loadOrCreate(USER_ID)).isSameAs(winner);
    }

    private static UserState state(long id) {
        UserState state = new UserState();
        state.setId(id);
        state.setLineUserId(USER_ID);
        return state;
    }
}
