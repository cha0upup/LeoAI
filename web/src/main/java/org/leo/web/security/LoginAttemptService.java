package org.leo.web.security;

import org.leo.service.config.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
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
        String key = key(username, remoteAddress);
        AttemptState state = attempts.get(key);
        if (state == null) return 0L;
        if (state.lockedUntil <= 0L) return 0L;
        long remainingMs = state.lockedUntil - nowMillis.getAsLong();
        if (remainingMs <= 0L) {
            attempts.remove(key, state);
            return 0L;
        }
        return Math.max(1L, (remainingMs + 999L) / 1000L);
    }

    public void recordFailure(String username, String remoteAddress) {
        if (attempts.size() >= MAX_TRACKED_KEYS) evictExpiredOrOne();
        int maxAttempts = configService.getInt("security.login.max.attempts", DEFAULT_MAX_ATTEMPTS, 1, 100);
        long lockSeconds = configService.getInt("security.login.lock.seconds",
                (int) DEFAULT_LOCK_SECONDS, 1, 86_400);
        long now = nowMillis.getAsLong();
        attempts.compute(key(username, remoteAddress), (ignored, current) -> {
            if (current == null || (current.lockedUntil > 0L && current.lockedUntil <= now)) {
                current = new AttemptState(0, 0L);
            }
            int failures = current.failures + 1;
            long lockedUntil = failures >= maxAttempts ? now + lockSeconds * 1000L : current.lockedUntil;
            return new AttemptState(failures, lockedUntil);
        });
    }

    public void recordSuccess(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
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
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String remote = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        return user + '\n' + remote;
    }

    private record AttemptState(int failures, long lockedUntil) {
    }
}
