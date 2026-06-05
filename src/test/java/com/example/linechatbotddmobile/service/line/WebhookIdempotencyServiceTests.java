package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.ProcessedWebhookEvent;
import com.example.linechatbotddmobile.repository.ProcessedWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookIdempotencyServiceTests {

    private ProcessedWebhookEventRepository repository;
    private WebhookIdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ProcessedWebhookEventRepository.class);
        service = new WebhookIdempotencyService(repository);
    }

    @Test
    void firstSightingReturnsTrueAndSavesEvent() {
        when(repository.save(any(ProcessedWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean firstTime = service.markAsProcessed("evt-123");

        assertThat(firstTime).isTrue();
        verify(repository, times(1)).save(any(ProcessedWebhookEvent.class));
    }

    @Test
    void duplicateRowReturnsFalseAndSkips() {
        when(repository.save(any(ProcessedWebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("PK conflict"));

        boolean firstTime = service.markAsProcessed("evt-dup");

        assertThat(firstTime).isFalse();
    }

    @Test
    void nullEventIdAllowsProcessingWithoutPersisting() {
        boolean firstTime = service.markAsProcessed(null);

        assertThat(firstTime).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void blankEventIdAllowsProcessingWithoutPersisting() {
        boolean firstTime = service.markAsProcessed("   ");

        assertThat(firstTime).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void unexpectedRepositoryFailureFailsOpenAndAllowsEvent() {
        when(repository.save(any(ProcessedWebhookEvent.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        boolean firstTime = service.markAsProcessed("evt-failopen");

        // Fail open: rather than block a real event because the idempotency store
        // is broken, allow the event through. Real events > strict dedup.
        assertThat(firstTime).isTrue();
    }
}
