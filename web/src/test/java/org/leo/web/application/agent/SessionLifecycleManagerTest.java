package org.leo.web.application.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.discovery.NetworkProbeWorkflowService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionLifecycleManagerTest {
    @TempDir Path vfs;
    private final NetworkProbeWorkflowService workflow = mock(NetworkProbeWorkflowService.class);
    private final SessionLifecycleManager lifecycle = new SessionLifecycleManager(workflow);

    @AfterEach
    void clearSessions() {
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void canonicalExpiryClosesOnlyExpiredSessionsCleansTheirDirectoryAndFiresModuleCleanup() throws Exception {
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
        AgentNodeSession session = session("active");
        lifecycle.register(session);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.evictExpired(0));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.evictExpired(-1));
        assertSame(session, AgentNodeSessionContainer.getSession("active"));
        verifyNoInteractions(workflow);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsVisibleProjectSessionsFromOneSnapshot(boolean admin) {
        User user = new User();
        user.setUserId("user");
        user.setPrivilege(admin ? "admin" : "normal");
        try (var sessions = mockStatic(AgentNodeSessionContainer.class)) {
            sessions.when(AgentNodeSessionContainer::getAllSessions).thenReturn(Map.of(
                    "own-1", projectSession("project-1", "user"),
                    "own-2", projectSession("project-1", "user"),
                    "foreign", projectSession("project-1", "other"),
                    "no-owner", projectSession("project-2", null),
                    "unassigned", projectSession(null, "user")));

            assertEquals(admin ? Map.of("project-1", 3, "project-2", 1) : Map.of("project-1", 2),
                    lifecycle.countByProject(user));
            sessions.verify(AgentNodeSessionContainer::getAllSessions);
        }
    }

    @Test
    void detachingAProjectKeepsSessionsExecutionsAndOtherProjectsAlive() {
        AgentNodeSession first = session("first");
        first.setProjectId("removed");
        var thread = first.createAiThread("thread", "test");
        assertTrue(thread.claimExecution());
        AgentNodeSession foreign = session("foreign");
        foreign.setCreateByUser("other");
        foreign.setProjectId("removed");
        AgentNodeSession kept = session("kept");
        kept.setProjectId("kept-project");
        lifecycle.register(first);
        lifecycle.register(foreign);
        lifecycle.register(kept);

        assertEquals(2, lifecycle.detachProject("removed"));

        assertNull(first.getProjectId());
        assertNull(foreign.getProjectId());
        assertEquals("kept-project", kept.getProjectId());
        assertSame(first, AgentNodeSessionContainer.getSession("first"));
        assertSame(foreign, AgentNodeSessionContainer.getSession("foreign"));
        assertSame(kept, AgentNodeSessionContainer.getSession("kept"));
        assertTrue(thread.isExecuting());
        verify(first, never()).close();
        verify(foreign, never()).close();
        verify(kept, never()).close();
        verifyNoInteractions(workflow);
        assertEquals(0, lifecycle.detachProject("removed"));
    }

    private AgentNodeSession projectSession(String projectId, String ownerId) {
        AgentNodeSession session = new AgentNodeSession();
        session.setProjectId(projectId);
        session.setCreateByUser(ownerId);
        return session;
    }

    private AgentNodeSession session(String id) {
        AgentNodeSession session = spy(new AgentNodeSession());
        session.setSessionId(id);
        session.setCreateByUser("user");
        return session;
    }
}
