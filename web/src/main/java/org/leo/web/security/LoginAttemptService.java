package org.leo.web.security;

import org.leo.service.config.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** In-memory brute-force throttle keyed by normalized username and source IP. */
@Service
public class LoginAttemptService {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_LOCK_SECONDS = 300L;
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final SystemConfigService configService;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(SystemConfigService configService) {
        this(configService, System::currentTimeMillis);
    }

    LoginAttemptService(SystemConfigService configService, LongSupplier nowMillis) {
        this.configService = configService;
        this.nowMillis = nowMillis;
    }

    public long retryAfterSeconds(String username, String remoteAddress) {
        AttemptState state = attempts.get(key(username, remoteAddress));
        if (state == null) return 0L;
        if (state.lockedUntil <= 0L) return 0L;
        long remainingMs = state.lockedUntil - nowMillis.getAsLong();
        if (remainingMs <= 0L) {
            attempts.remove(key(username, remoteAddress), state);
            return 0L;
        }
        return Math.max(1L, (remainingMs + 999L) / 1000L);
    }

    public void recordFailure(String username, String remoteAddress) {
        if (attempts.size() >= MAX_TRACKED_KEYS) evictExpiredOrOne();
        int maxAttempts = intConfig("security.login.max.attempts", DEFAULT_MAX_ATTEMPTS, 1, 100);
        long lockSeconds = intConfig("security.login.lock.seconds",
                (int) DEFAULT_LOCK_SECONDS, 1, 86_400);
        long now = nowMillis.getAsLong();
        attempts.compute(key(username, remoteAddress), (ignored, current) -> {
            AttemptState state = current == null || (current.lockedUntil > 0L && current.lockedUntil <= now)
                    ? new AttemptState() : current;
            state.failures++;
            if (state.failures >= maxAttempts) {
                state.lockedUntil = now + lockSeconds * 1000L;
            }
            return state;
        });
    }

    public void recordSuccess(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
    }

    private int intConfig(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(configService.getString(key, String.valueOf(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void evictExpiredOrOne() {
        long now = nowMillis.getAsLong();
        attempts.entrySet().removeIf(entry -> entry.getValue().lockedUntil > 0L
                && entry.getValue().lockedUntil <= now);
        if (attempts.size() < MAX_TRACKED_KEYS) return;
        for (Map.Entry<String, AttemptState> entry : attempts.entrySet()) {
            attempts.remove(entry.getKey(), entry.getValue());
            break;
        }
    }

    private static String key(String username, String remoteAddress) {
        String user = username == null ? "" : username.trim().toLowerCase();
        String remote = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        return user + '\n' + remote;
    }

    private static final class AttemptState {
        private int failures;
        private long lockedUntil;
    }
}
