package org.leo.core.repository.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentRepositoryTest {

    @TempDir
    Path vfsRoot;

    private String previousVfsPath;

    @BeforeEach
    void setUp() throws Exception {
        previousVfsPath = setVfsPath(vfsRoot.toString());
        AgentNodeSessionContainer.clearAllSessions();
    }

    @AfterEach
    void tearDown() throws Exception {
        AgentNodeSessionContainer.clearAllSessions();
        setVfsPath(previousVfsPath);
    }

    @Test
    void hostCacheIsIsolatedByHostIdAndDiscoveryUsesFingerprint() {
        AgentNodeSession session = session("session-a", "agent-a", "host-a");
        AgentHostCacheRepository repository = new AgentHostCacheRepository(new AtomicFileStore());

        repository.saveBasicInfo(session.getSessionId(), "host-a", Map.of("hostname", "alpha"));
        repository.saveBasicInfo(session.getSessionId(), "host-b", Map.of("hostname", "beta"));

        assertEquals("alpha", repository.loadBasicInfo("user-a", "agent-a", "host-a").get("hostname"));
        assertEquals("beta", repository.loadBasicInfo("user-a", "agent-a", "host-b").get("hostname"));
        assertEquals(List.of("host-a", "host-b"), repository.listCachedHostIds("user-a", "agent-a"));

        repository.saveHostDiscovery("user-a", "agent-a", "fingerprint-1",
                List.of("host-a", "host-a", "host-b"));
        assertEquals(List.of("host-a", "host-b"), repository.loadHostDiscovery(
                "user-a", "agent-a", "fingerprint-1").get("hostIds"));
        assertNull(repository.loadHostDiscovery("user-a", "agent-a", "fingerprint-2"));
    }

    @Test
    void reconRepositoryPersistsAndSynchronizesOnlyTheSameHost() {
        AgentNodeSession first = session("session-a", "agent-a", "host-a");
        AgentNodeSession second = session("session-b", "agent-a", "host-a");
        AgentNodeSession differentHost = session("session-d", "agent-a", "host-b");
        AgentNodeSession other = session("session-c", "agent-b", "host-c");
        AgentReconRepository repository = new AgentReconRepository(new AtomicFileStore());

        assertEquals("first", repository.append(first.getSessionId(), "first"));
        assertEquals("first\n\nsecond", repository.append(first.getSessionId(), "second"));
        assertEquals("first\n\nsecond", second.getReconSummary());
        assertNull(other.getReconSummary());
        assertNull(differentHost.getReconSummary());
        assertEquals("first\n\nsecond", repository.load("user-a", "agent-a", "host-a"));

        repository.save(first.getSessionId(), "replacement");
        assertEquals("replacement", first.getReconSummary());
        assertEquals("replacement", second.getReconSummary());
        assertNull(repository.load("user-a", "agent-b", "host-a"));
    }

    @Test
    void reconSummarySurvivesReconnectWithoutImportingUnscopedHistory() throws Exception {
        AgentNodeSession first = session("first", "agent-a", "host/../a");
        AgentNodeSession anotherUser = session("other-user", "agent-a", "host/../a");
        anotherUser.setCreateByUser("user-b");
        File oldFile = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir("user-a", "agent-a"), "recon-summary.md");
        Files.writeString(oldFile.toPath(), "legacy host unknown");
        AgentReconRepository repository = new AgentReconRepository(new AtomicFileStore());
        assertNull(repository.load(first.getCreateByUser(), first.resolveAgentId(), first.getCurrentHostId()));
        File saved = repository.save(first.getSessionId(), " host a ");
        assertNotNull(saved);
        assertTrue(saved.toPath().startsWith(oldFile.toPath().getParent().resolve("recon-summary")));
        assertNull(anotherUser.getReconSummary());

        AgentNodeSessionContainer.removeSession(first.getSessionId());
        AgentNodeSession reconnected = session("reconnected", "agent-a", "host/../a");
        assertEquals("host a", repository.load(reconnected.getSessionId()));
        assertNull(repository.load("user-a", "agent-a", "other-host"));
        assertNull(repository.load("user-b", "agent-a", "host/../a"));
        repository.save(reconnected.getSessionId(), null);
        assertNull(repository.load(reconnected.getSessionId()));
        assertEquals("legacy host unknown", Files.readString(oldFile.toPath()));
    }

    @Test
    void failedWritesLeaveThePreviousMemoryAndDiskSummaryIntact() throws Exception {
        AgentNodeSession first = session("first", "agent-a", "host-a");
        AtomicFileStore files = spy(new AtomicFileStore());
        AgentReconRepository repository = new AgentReconRepository(files);
        repository.save(first.getSessionId(), "original");
        doThrow(new java.io.IOException("fixture write failure")).when(files).writeText(any(), any());
        assertThrows(IllegalStateException.class, () -> repository.save(first.getSessionId(), "replacement"));
        assertThrows(IllegalStateException.class, () -> repository.append(first.getSessionId(), "addition"));
        assertEquals("original", first.getReconSummary());
        assertEquals("original", repository.load(first.getCreateByUser(), first.resolveAgentId(), first.getCurrentHostId()));
    }

    @Test
    void reconRequiresKnownUserAndHost() {
        AgentNodeSession first = new AgentNodeSession();
        first.setSessionId("unbound");
        first.setCreateByUser("user-a");
        first.setAgentId("agent-a");
        AgentNodeSessionContainer.addSession("unbound", first);
        AgentReconRepository repository = new AgentReconRepository(new AtomicFileStore());
        assertThrows(IllegalStateException.class, () -> repository.save("unbound", "summary"));
        assertNull(repository.load("unbound"));
    }

    @Test
    void unreadableSummaryIsNotTreatedAsEmptyAndReplacedByAnAppend() throws Exception {
        AgentNodeSession first = session("first", "agent-a", "host-a");
        AgentReconRepository repository = new AgentReconRepository(new AtomicFileStore());
        File file = repository.save("first", "original");
        byte[] corrupt = {(byte) 0xC3, (byte) 0x28};
        Files.write(file.toPath(), corrupt);
        org.junit.jupiter.api.Assertions.assertEquals(FileStoreException.Reason.INVALID_CONTENT,
                assertThrows(FileStoreException.class, () -> repository.load("first")).getReason());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.append("first", "addition"));
        assertTrue(error.getCause() instanceof FileStoreException);
        org.junit.jupiter.api.Assertions.assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
        assertEquals("original", first.getReconSummary());
    }

    @Test
    void webRuntimeSnapshotsAreIsolatedByHostId() {
        AgentNodeSession first = session("session-a", "agent-a", "host-a");
        AgentNodeSession second = session("session-b", "agent-a", "host-b");
        AgentWebRuntimeRepository repository = new AgentWebRuntimeRepository(new AtomicFileStore());

        repository.save(first.getSessionId(), Map.of("schemaVersion", 2, "runtime", "tomcat"));
        repository.save(second.getSessionId(), Map.of("schemaVersion", 2, "runtime", "jetty"));

        assertEquals("tomcat", repository.load(first.getCreateByUser(), first.resolveAgentId(), first.getCurrentHostId()).get("runtime"));
        assertEquals("jetty", repository.load(second.getCreateByUser(), second.resolveAgentId(), second.getCurrentHostId()).get("runtime"));
        assertEquals("host-a", repository.load(first.getCreateByUser(), first.resolveAgentId(), first.getCurrentHostId()).get("hostId"));
        assertEquals("host-b", repository.load(second.getCreateByUser(), second.resolveAgentId(), second.getCurrentHostId()).get("hostId"));
    }

    @Test
    void webRuntimeRepositoryRejectsOldSnapshotVersions() throws Exception {
        AgentNodeSession current = session("session-old", "agent-old", "host-old");
        AtomicFileStore files = new AtomicFileStore();
        AgentWebRuntimeRepository repository = new AgentWebRuntimeRepository(files);

        assertNull(repository.save(current.getSessionId(), Map.of("schemaVersion", 1, "runtime", "old")));
        assertNull(repository.save(current.getSessionId(), Map.of("schemaVersion", 2.5, "runtime", "old")));
        File file = repository.save(current.getSessionId(), Map.of("schemaVersion", 2, "runtime", "current"));
        assertEquals("current", repository.load(current.getCreateByUser(), current.resolveAgentId(), current.getCurrentHostId()).get("runtime"));

        files.writeJson(file, Map.of("schemaVersion", 1, "runtime", "old"));
        assertNull(repository.load(current.getCreateByUser(), current.resolveAgentId(), current.getCurrentHostId()));
    }

    private AgentNodeSession session(String sessionId, String agentId, String hostId) {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(sessionId);
        session.setCreateByUser("user-a");
        session.setAgentId(agentId);
        session.bindHostId(hostId);
        AgentNodeSessionContainer.addSession(sessionId, session);
        return session;
    }

    private String setVfsPath(String path) throws Exception {
        Field field = LeoConfig.class.getDeclaredField("VFS_PATH");
        field.setAccessible(true);
        String previous = (String) field.get(null);
        field.set(null, path);
        return previous;
    }
}
