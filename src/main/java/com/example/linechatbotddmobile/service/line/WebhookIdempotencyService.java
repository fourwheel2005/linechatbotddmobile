package com.example.linechatbotddmobile.service.line;

import com.example.linechatbotddmobile.entity.ProcessedWebhookEvent;
import com.example.linechatbotddmobile.repository.ProcessedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Deduplicates LINE webhook events by their webhookEventId.
 *
 * Strategy: optimistic INSERT.
 *   - PK constraint on event_id ensures only one writer wins under race.
 *   - DataIntegrityViolationException → already processed → skip.
 *
 * Cleanup: rows older than {@link #RETENTION_HOURS} hours are pruned hourly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIdempotencyService {

    private static final long RETENTION_HOURS = 24L;
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L; // 1 hour

    private final ProcessedWebhookEventRepository repository;

    /**
     * Returns true if this is the first time we have seen {@code eventId}
     * (and the row was successfully inserted). Returns false if it was
     * already processed before (duplicate / LINE retry).
     *
     * If {@code eventId} is null or blank, returns true (no idempotency check possible).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAsProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        try {
            repository.save(new ProcessedWebhookEvent(eventId, LocalDateTime.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("🔁 Duplicate webhook event detected, skipping: eventId={}", eventId);
            return false;
        } catch (Exception e) {
            // Never block a real event because of an idempotency-store failure.
            log.warn("⚠️ Idempotency store failure for eventId={}, allowing event through: {}",
                    eventId, e.toString());
            return true;
        }
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, initialDelay = CLEANUP_INTERVAL_MS)
    @SchedulerLock(name = "webhookIdempotencyCleanup",
            lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    @Transactional
    public void cleanupOldRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("🧹 Cleaned up {} processed_webhook_events older than {}h", deleted, RETENTION_HOURS);
        }
    }
}
