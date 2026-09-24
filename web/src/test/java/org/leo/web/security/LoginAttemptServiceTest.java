package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.service.config.SystemConfigService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.atomic.AtomicLong;

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
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString("security.login.max.attempts", "5")).thenReturn("2");
        when(config.getString("security.login.lock.seconds", "300")).thenReturn("60");
        AtomicLong now = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(config, now::get);

        service.recordFailure("Admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
        service.recordFailure("admin", "127.0.0.1");
        assertEquals(60L, service.retryAfterSeconds("ADMIN", "127.0.0.1"));

        service.recordSuccess("admin", "127.0.0.1");
        assertEquals(0L, service.retryAfterSeconds("admin", "127.0.0.1"));
    }
}
