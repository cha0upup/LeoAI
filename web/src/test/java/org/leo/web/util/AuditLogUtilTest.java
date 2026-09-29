package org.leo.web.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.AuditLog;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogUtilTest {

    private final AuditLogUtil audit = new AuditLogUtil();

    @AfterEach
    void clearStaticDependencies() {
        audit.setAuditLogService(null);
        audit.setAuditPolicyService(null);
        audit.setApplicationContext(null);
    }

    @Test
    void webAuditUsesTheSameRedactionForUrlAndNestedSecrets() {
        AuditLogService logs = mock(AuditLogService.class);
        AuditPolicyService policy = mock(AuditPolicyService.class);
        when(policy.shouldRecord("TEST", false)).thenReturn(true);
        audit.setAuditLogService(logs);
        audit.setAuditPolicyService(policy);
        AuditLogUtil.logSystemOperation(null, "TEST", "test", "/test",
                Map.of("url", "https://user:url-secret@example.test?token=query-secret",
                        "nested", Map.of("api_key", "key-secret")),
                200, "ok", null, null, "127.0.0.1", false);

        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(logs).insertAuditLog(captured.capture());
        AuditLog record = captured.getValue();
        assertEquals("SUCCESS", record.getStatus());
        assertEquals("127.0.0.1", record.getClientIp());
        assertFalse(record.getRequestParams().contains("url-secret"));
        assertFalse(record.getRequestParams().contains("query-secret"));
        assertFalse(record.getRequestParams().contains("key-secret"));
        assertTrue(record.getRequestParams().contains("example.test"));
    }
}
