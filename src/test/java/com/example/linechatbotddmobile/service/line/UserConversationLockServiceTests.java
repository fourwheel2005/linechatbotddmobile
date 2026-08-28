package com.example.linechatbotddmobile.service.line;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UserConversationLockServiceTests {

    private static final String USER_ID = "U-lock-test";

    private final UserConversationLockService service = new UserConversationLockService();

    @Test
    void sameUsersTasksNeverRunAtTheSameTime() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> service.runLocked(USER_ID, () -> {
                firstEntered.countDown();
                await(releaseFirst);
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() ->
                    service.runLocked(USER_ID, secondEntered::countDown));

            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            first.get();
            second.get();
            assertThat(secondEntered.getCount()).isZero();
            assertThat(service.activeLockCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void differentUsersCanRunInParallel() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> service.runLocked("U-A", () -> {
                firstEntered.countDown();
                await(releaseFirst);
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() ->
                    service.runLocked("U-B", secondEntered::countDown));

            assertThat(secondEntered.await(1, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            first.get();
            second.get();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lockIsReentrantForNestedControllerAndFlowCalls() {
        String result = service.callLocked(USER_ID, () ->
                service.callLocked(USER_ID, () -> "completed"));

        assertThat(result).isEqualTo("completed");
        assertThat(service.activeLockCount()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
