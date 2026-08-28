package com.example.linechatbotddmobile.service.line;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Prevents two workers in this application instance from mutating one customer's flow concurrently.
 * The database unique index remains the cross-instance safety net for state creation.
 */
@Service
public class UserConversationLockService {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    public void runLocked(String userId, Runnable action) {
        callLocked(userId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callLocked(String userId, Supplier<T> action) {
        if (userId == null || userId.isBlank()) {
            return action.get();
        }

        LockEntry entry = retainLock(userId);
        entry.lock().lock();
        try {
            return action.get();
        } finally {
            entry.lock().unlock();
            releaseLock(userId, entry);
        }
    }

    int activeLockCount() {
        return locks.size();
    }

    private LockEntry retainLock(String userId) {
        return locks.compute(userId, (ignored, current) -> {
            LockEntry entry = current != null ? current : new LockEntry();
            entry.retain();
            return entry;
        });
    }

    private void releaseLock(String userId, LockEntry expected) {
        locks.computeIfPresent(userId, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            return current.release() == 0 ? null : current;
        });
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;

        ReentrantLock lock() {
            return lock;
        }

        void retain() {
            references++;
        }

        int release() {
            return --references;
        }
    }
}
