package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionLifecycleManagerTest {
    @TempDir Path vfs;

    @AfterEach
    void clearSessions() {
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void canonicalExpiryClosesOnlyExpiredSessionsCleansTheirDirectoryAndFiresModuleCleanup() throws Exception {
        NetworkProbeWorkflowService workflow = mock(NetworkProbeWorkflowService.class);
        SessionLifecycleManager lifecycle = new SessionLifecycleManager(workflow);
        AgentNodeSession expired = session("expired");
        when(expired.getLastActiveTime()).thenReturn(0L);
        AgentNodeSession active = session("active");
        Path expiredDir = Files.createDirectories(vfs.resolve("users/user/sessions/expired"));
        Path activeDir = Files.createDirectories(vfs.resolve("users/user/sessions/active"));
        Path sharedAgent = Files.createDirectories(vfs.resolve("users/user/agents/agent"));
        Files.writeString(expiredDir.resolve("file-cache.txt"), "expired cache");
        Files.writeString(activeDir.resolve("file-cache.txt"), "active cache");
        Files.writeString(sharedAgent.resolve("retained-summary.txt"), "persistent data");
        lifecycle.registerDestroyHook();
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(vfs.toString());
            lifecycle.register(expired);
            lifecycle.register(active);
            assertEquals(List.of("expired"), lifecycle.evictExpired(60_000));
            assertNull(AgentNodeSessionContainer.getSession("expired"));
            assertSame(active, AgentNodeSessionContainer.getSession("active"));
            verify(expired).close();
            verify(active, never()).close();
            verify(workflow).cleanupSession("expired");
            verifyNoMoreInteractions(workflow);
            assertFalse(Files.exists(expiredDir));
            assertTrue(Files.exists(activeDir.resolve("file-cache.txt")));
            assertEquals("persistent data", Files.readString(sharedAgent.resolve("retained-summary.txt")));
            assertTrue(lifecycle.evictExpired(60_000).isEmpty());
        } finally {
            lifecycle.unregisterDestroyHook();
        }
    }

    @Test
    void invalidExpiryIntervalsLeaveRegisteredSessionsUntouched() {
        NetworkProbeWorkflowService workflow = mock(NetworkProbeWorkflowService.class);
        SessionLifecycleManager lifecycle = new SessionLifecycleManager(workflow);
        AgentNodeSession session = session("active");
        lifecycle.register(session);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.evictExpired(0));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.evictExpired(-1));
        assertSame(session, AgentNodeSessionContainer.getSession("active"));
        verifyNoInteractions(workflow);
    }

    private AgentNodeSession session(String id) {
        AgentNodeSession session = spy(new AgentNodeSession());
        session.setSessionId(id);
        session.setCreateByUser("user");
        return session;
    }
}
