package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.User;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAuditServiceTest {

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
