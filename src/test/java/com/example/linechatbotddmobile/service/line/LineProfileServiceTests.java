package com.example.linechatbotddmobile.service.line;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineProfileServiceTests {

    private MessagingApiClient messagingApiClient;
    private LineProfileService service;

    @BeforeEach
    void setUp() {
        messagingApiClient = Mockito.mock(MessagingApiClient.class);
        // Disable retry waits and circuit breaker transitions for unit testing
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(100)
                .slidingWindowSize(100)
                .build());
        service = new LineProfileService(messagingApiClient, retryRegistry, cbRegistry);
    }

    @Test
    void returnsDisplayNameWhenApiSucceeds() {
        givenProfileResponds("U1", "Alice");

        String name = service.getDisplayName("U1");

        assertThat(name).isEqualTo("Alice");
        verify(messagingApiClient, times(1)).getProfile("U1");
    }

    @Test
    void cachesSuccessfulLookupAcrossCalls() {
        givenProfileResponds("U1", "Alice");

        String first = service.getDisplayName("U1");
        String second = service.getDisplayName("U1");

        assertThat(first).isEqualTo("Alice");
        assertThat(second).isEqualTo("Alice");
        verify(messagingApiClient, times(1)).getProfile("U1");
    }

    @Test
    void returnsDefaultWhenApiThrows() {
        CompletableFuture<Result<UserProfileResponse>> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("LINE unavailable"));
        when(messagingApiClient.getProfile("U-error")).thenReturn(failing);

        String name = service.getDisplayName("U-error");

        assertThat(name).isEqualTo("ลูกค้า");
    }

    @Test
    void returnsDefaultOnTimeout() {
        // never completes -> .get(5s, SECONDS) will time out; we can simulate
        // by returning a future that never completes — but to keep tests fast,
        // wrap a future that completes exceptionally with TimeoutException.
        CompletableFuture<Result<UserProfileResponse>> failing = new CompletableFuture<>();
        failing.completeExceptionally(new TimeoutException("simulated timeout"));
        when(messagingApiClient.getProfile("U-timeout")).thenReturn(failing);

        String name = service.getDisplayName("U-timeout");

        assertThat(name).isEqualTo("ลูกค้า");
        verify(messagingApiClient, atLeastOnce()).getProfile("U-timeout");
    }

    @Test
    void returnsDefaultForNullOrBlankUserId() {
        assertThat(service.getDisplayName(null)).isEqualTo("ลูกค้า");
        assertThat(service.getDisplayName("")).isEqualTo("ลูกค้า");
        assertThat(service.getDisplayName("   ")).isEqualTo("ลูกค้า");
        verify(messagingApiClient, never()).getProfile(any());
    }

    @Test
    void doesNotCacheFailureSoNextCallCanRetry() {
        // Build a fresh service with no retries so we can observe the fail path cleanly.
        RetryRegistry noRetry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1).waitDuration(Duration.ofMillis(1)).build());
        CircuitBreakerRegistry cb = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(100).slidingWindowSize(100).build());
        LineProfileService freshService = new LineProfileService(messagingApiClient, noRetry, cb);

        CompletableFuture<Result<UserProfileResponse>> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("transient"));
        when(messagingApiClient.getProfile("U-mixed"))
                .thenReturn(failing)
                .thenReturn(successFuture("Bob"));

        String first = freshService.getDisplayName("U-mixed");
        String second = freshService.getDisplayName("U-mixed");

        assertThat(first).isEqualTo("ลูกค้า");
        assertThat(second).isEqualTo("Bob");
    }

    // --- helpers ---
    private void givenProfileResponds(String userId, String displayName) {
        when(messagingApiClient.getProfile(userId)).thenReturn(successFuture(displayName));
    }

    private CompletableFuture<Result<UserProfileResponse>> successFuture(String displayName) {
        UserProfileResponse profile = new UserProfileResponse(displayName, "U-test", null, null, null);
        Result<UserProfileResponse> result = new Result<>("req-id", null, profile);
        return CompletableFuture.completedFuture(result);
    }
}
