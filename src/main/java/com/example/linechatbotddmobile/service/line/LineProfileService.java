package com.example.linechatbotddmobile.service.line;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LineProfileService {

    static final String DEFAULT_CUSTOMER_NAME = "ลูกค้า";
    private static final long LINE_PROFILE_TIMEOUT_SEC = 5L;
    private static final Duration CACHE_TTL = Duration.ofMinutes(60);
    private static final int CACHE_MAX_SIZE = 20_000;

    private final MessagingApiClient messagingApiClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    private final Map<String, CachedName> cache = new ConcurrentHashMap<>();

    public LineProfileService(MessagingApiClient messagingApiClient,
                              RetryRegistry retryRegistry,
                              CircuitBreakerRegistry circuitBreakerRegistry) {
        this.messagingApiClient = messagingApiClient;
        this.retry = retryRegistry.retry("lineProfile");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("lineProfile");
    }

    public String getDisplayName(String userId) {
        if (userId == null || userId.isBlank()) return DEFAULT_CUSTOMER_NAME;

        CachedName cached = cache.get(userId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.value;
        }

        try {
            String name = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, () -> fetchDisplayNameBlocking(userId)))
                    .get();

            if (name != null && !name.isBlank()) {
                putCache(userId, name, now);
                return name;
            }
            return DEFAULT_CUSTOMER_NAME;
        } catch (Exception e) {
            log.warn("⚠️ getDisplayName fallback for userId={} : {}", userId, e.toString());
            return DEFAULT_CUSTOMER_NAME;
        }
    }

    private String fetchDisplayNameBlocking(String userId) {
        try {
            return messagingApiClient.getProfile(userId)
                    .get(LINE_PROFILE_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .body()
                    .displayName();
        } catch (Exception e) {
            throw new LineProfileException(e);
        }
    }

    private void putCache(String userId, String value, Instant now) {
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
        cache.put(userId, new CachedName(value, now.plus(CACHE_TTL)));
    }

    private record CachedName(String value, Instant expiresAt) {}

    private static class LineProfileException extends RuntimeException {
        LineProfileException(Throwable cause) { super(cause); }
    }
}
