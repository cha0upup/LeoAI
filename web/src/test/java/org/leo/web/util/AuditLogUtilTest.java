package org.leo.web.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogUtilTest {

    private final AuditLogUtil audit = new AuditLogUtil();

    @AfterEach
    void clearStaticDependencies() {
        audit.setAuditLogService(null);
        audit.setAuditPolicyService(null);
    }

    @Test
    void webAuditUsesTheSameRedactionForUrlAndNestedSecrets() {
        AuditLogService logs = recordingLogs();
        AuditLogUtil.logSystemOperation(null, "IGNORED", "test", "/test", null,
                200, "ok", null, null, "127.0.0.1", false);
        verifyNoInteractions(logs);

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

    @Test
    void bothAuditEntriesShareSessionExtractionWithoutLosingUserOrAgentIdentity() {
        AuditLogService logs = recordingLogs();
        User user = new User();
        user.setUserId("user");
        user.setUserName("name");
        Agent agent = new Agent();
        agent.setAgentId("agent");
        agent.setAgentName("host");
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(node.getUser()).thenReturn(user);
        when(node.getAgent()).thenReturn(agent);
        Map<String, Object> params = Map.of("sessionId", 17, "password", "secret");

        AuditLogUtil.logOperation(node, "TEST", "test", "/test", params, 200, "ok", null, null, "ip");
        AuditLogUtil.logSystemOperation(user, "TEST", "test", "/test", params, 200, "ok", null, null, "ip", false);

        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(logs, times(2)).insertAuditLog(captured.capture());
        for (AuditLog record : captured.getAllValues()) {
            assertEquals("17", record.getSessionId());
            assertEquals("user", record.getUserId());
            assertEquals("name", record.getUserName());
            assertEquals("SUCCESS", record.getStatus());
            assertFalse(record.getRequestParams().contains("secret"));
        }
        assertEquals("agent", captured.getAllValues().get(0).getAgentId());
        assertEquals("host", captured.getAllValues().get(0).getAgentName());
        assertNull(captured.getAllValues().get(1).getAgentId());
    }

    @ParameterizedTest
    @MethodSource("requestsWithoutSession")
    void missingSessionIdsStayNullForBothAuditEntries(Map<String, Object> params) {
        AuditLogService logs = recordingLogs();
        AuditLogUtil.logOperation(null, "TEST", "test", "/test", params, 200, "ok", null, null, "ip");
        AuditLogUtil.logSystemOperation(null, "TEST", "test", "/test", params, 200, "ok", null, null, "ip", false);
        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(logs, times(2)).insertAuditLog(captured.capture());
        for (AuditLog record : captured.getAllValues()) assertNull(record.getSessionId());
    }

    private static Stream<Map<String, Object>> requestsWithoutSession() {
        return Stream.of(null, Map.of(), Collections.singletonMap("sessionId", null));
    }

    private AuditLogService recordingLogs() {
        AuditLogService logs = mock(AuditLogService.class);
        AuditPolicyService policy = mock(AuditPolicyService.class);
        when(policy.shouldRecord("TEST", false)).thenReturn(true);
        audit.setAuditLogService(logs);
        audit.setAuditPolicyService(policy);
        return logs;
    }
}
