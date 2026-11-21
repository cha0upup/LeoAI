package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.User;
import org.leo.service.config.SystemConfigService;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAuditServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksAuditModeAfterTheMonotonicCacheInterval(boolean publishedByWrite) {
        var config = mock(SystemConfigService.class);
        var now = new AtomicLong(-TimeUnit.SECONDS.toNanos(10));
        var policy = new AuditPolicyService(config, now::get);
        var logs = mock(AuditLogService.class);
        var service = new AgentAuditService(logs, policy, mock(UserService.class));
        when(config.getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON))
                .thenReturn(publishedByWrite ? AuditPolicyService.MODE_ON : AuditPolicyService.MODE_WRITE,
                        AuditPolicyService.MODE_ON);
        if (publishedByWrite) policy.updateMode(AuditPolicyService.MODE_WRITE);

        service.logSuccess(null, null, "FILE_LIST", "list", "/fixture", null, "ok");
        now.addAndGet(TimeUnit.SECONDS.toNanos(3));
        service.logSuccess(null, null, "FILE_LIST", "list", "/fixture", null, "ok");
        verifyNoInteractions(logs);
        verify(config, times(publishedByWrite ? 0 : 1))
                .getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON);

        now.incrementAndGet();
        service.logSuccess(null, null, "FILE_LIST", "list", "/fixture", null, "ok");
        verify(logs).insertAuditLog(any(AuditLog.class));
        verify(config, times(publishedByWrite ? 1 : 2))
                .getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON);
    }

    @Test
    void recordsTheAuthenticatedActorForSharedSessionOperations() {
        AuditLogService logs = mock(AuditLogService.class);
        AuditPolicyService policy = mock(AuditPolicyService.class);
        when(policy.shouldRecord("SSH_PROFILE_SAVE", false)).thenReturn(true);
        User actor = new User(); actor.setUserId("actual-actor"); actor.setUserName("Operator");
        AgentAuditService service = new AgentAuditService(logs, policy, mock(UserService.class));
        service.logOperationAs(actor, "session", null, "SSH_PROFILE_SAVE", "save", "profile",
                Map.of("profileId", "profile"), 200, "ok", "SUCCESS", null);
        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(logs).insertAuditLog(captured.capture());
        assertEquals("actual-actor", captured.getValue().getUserId());
        assertEquals("Operator", captured.getValue().getUserName());
    }

    @Test
    void redactsParametersBeforePersistingTheAuditRecord() {
        AuditLogService logs = mock(AuditLogService.class);
        AuditPolicyService policy = mock(AuditPolicyService.class);
        when(policy.shouldRecord("TEST", false)).thenReturn(true);
        AgentAuditService service = new AgentAuditService(logs, policy, mock(UserService.class));
        service.logSuccess(null, null, "TEST", "test", "/test",
                Map.of("url", "https://user:url-secret@example.test?token=query-secret",
                        "api_key", "key-secret"), "ok");

        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(logs).insertAuditLog(captured.capture());
        AuditLog record = captured.getValue();
        assertEquals("SUCCESS", record.getStatus());
        assertEquals("AI_TOOL", record.getRemark());
        assertFalse(record.getRequestParams().contains("url-secret"));
        assertFalse(record.getRequestParams().contains("query-secret"));
        assertFalse(record.getRequestParams().contains("key-secret"));
        assertTrue(record.getRequestParams().contains("example.test"));
    }
}
