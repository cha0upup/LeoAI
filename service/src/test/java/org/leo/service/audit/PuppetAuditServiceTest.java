package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AuditLog;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PuppetAuditServiceTest {

    @Test
    void redactsParametersBeforePersistingTheAuditRecord() {
        AuditLogService logs = mock(AuditLogService.class);
        AuditPolicyService policy = mock(AuditPolicyService.class);
        when(policy.shouldRecord("TEST", false)).thenReturn(true);
        PuppetAuditService service = new PuppetAuditService(logs, policy, mock(UserService.class));
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
