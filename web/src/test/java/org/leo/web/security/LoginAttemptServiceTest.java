package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.service.config.SystemConfigService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {

    @Test
    void createsServiceThroughSpringConstructorInjection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SystemConfigService.class, () -> mock(SystemConfigService.class));
            context.register(LoginAttemptService.class);
            context.refresh();

            assertNotNull(context.getBean(LoginAttemptService.class));
        }
    }

    @Test
    void locksAfterConfiguredFailuresAndClearsAfterSuccess() {
        SystemConfigService config = config("2", "60");
        AtomicLong now = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(config, now::get);

        service.recordFailure("Admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
        service.recordFailure("admin", "127.0.0.1");
        assertEquals(60L, service.retryAfterSeconds("ADMIN", "127.0.0.1"));

        service.recordSuccess("admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
    }

    @Test
    void expiresLocksAndStartsANewAttemptCount() {
        AtomicLong now = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(config("2", "60"), now::get);
        service.recordFailure("admin", "127.0.0.1");
        service.recordFailure("admin", "127.0.0.1");
        now.addAndGet(59_001L);
        assertEquals(1L, service.retryAfterSeconds("admin", "127.0.0.1"));
        now.addAndGet(999L);
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
        service.recordFailure("admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
    }

    @Test
    void malformedConfigurationStillThrottlesLoginFailures() {
        LoginAttemptService service = new LoginAttemptService(config("bad", "bad"), () -> 1_000L);
        for (int index = 0; index < 4; index++) service.recordFailure("admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
        service.recordFailure("admin", "127.0.0.1");
        assertEquals(300L, service.retryAfterSeconds("admin", "127.0.0.1"));
    }

    @Test
    void increasingTheThresholdDoesNotReleaseAnExistingLock() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.findValueByKey("security.login.max.attempts")).thenReturn("1");
        when(mapper.findValueByKey("security.login.lock.seconds")).thenReturn("60");
        LoginAttemptService service = new LoginAttemptService(new SystemConfigService(mapper), () -> 1_000L);
        service.recordFailure("admin", "127.0.0.1");
        when(mapper.findValueByKey("security.login.max.attempts")).thenReturn("5");
        service.recordFailure("admin", "127.0.0.1");
        assertEquals(60L, service.retryAfterSeconds("admin", "127.0.0.1"));
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void usernameNormalizationIsIndependentOfServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            LoginAttemptService service = new LoginAttemptService(config("1", "60"), () -> 1_000L);
            service.recordFailure(" ADMIN ", "127.0.0.1");
            assertEquals(60L, service.retryAfterSeconds("admin", "127.0.0.1"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private SystemConfigService config(String attempts, String lockSeconds) {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.findValueByKey("security.login.max.attempts")).thenReturn(attempts);
        when(mapper.findValueByKey("security.login.lock.seconds")).thenReturn(lockSeconds);
        return new SystemConfigService(mapper);
    }
}
