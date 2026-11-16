package org.leo.core.infrastructure.persistence.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.config.LeoConfig;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentRepositoryTest {

    @TempDir
    Path vfsRoot;

    private MockedStatic<LeoConfig> config;

    @BeforeEach
    void setUp() {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(vfsRoot.toString());
        AgentNodeSessionContainer.clearAllSessions();
    }

    @AfterEach
    void tearDown() {
        try {
            AgentNodeSessionContainer.clearAllSessions();
        } finally {
            config.close();
        }
    }

    @Test
    void hostCacheIsIsolatedByHostIdAndDiscoveryUsesFingerprint() throws Exception {
        AgentNodeSession session = session("session-a", "agent-a", "host-a");
        AtomicFileStore files = spy(new AtomicFileStore());
        AgentHostCacheRepository repository = new AgentHostCacheRepository(files);
        assertEquals(new AgentHostCacheRepository.CacheSnapshot(List.of(), null),
                repository.readCache("user-a", "agent-a"));

        File first = repository.saveBasicInfo(session.getSessionId(), "host-a", Map.of("hostname", "alpha"));
        File second = repository.saveBasicInfo(session.getSessionId(), "host-b", Map.of("hostname", "beta"));
        Files.setLastModifiedTime(first.toPath(), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(second.toPath(), FileTime.fromMillis(2000));

        assertEquals("alpha", repository.loadBasicInfo("user-a", "agent-a", "host-a").get("hostname"));
        Map<String, Object> latest = repository.loadBasicInfo("user-a", "agent-a", "host-b");
        assertEquals("beta", latest.get("hostname"));
        clearInvocations(files);
        var cache = repository.readCache("user-a", "agent-a");
        assertEquals(2, cache.hostIds().size());
        assertEquals(Set.of("host-a", "host-b"), Set.copyOf(cache.hostIds()));
        assertEquals(latest.get("saveTime"), cache.saveTime());
        verify(files, times(2)).readJsonMap(any());

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

        repository.save(first.getSessionId(), " replacement ");
        assertEquals("replacement", first.getReconSummary());
        assertEquals("replacement", second.getReconSummary());
        assertNull(repository.load("user-a", "agent-b", "host-a"));

        repository.save(first.getSessionId(), " ");
        assertNull(first.getReconSummary());
        assertNull(second.getReconSummary());
        assertNull(repository.load(first.getSessionId()));
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
        assertEquals(FileStoreException.Reason.INVALID_CONTENT,
                assertThrows(FileStoreException.class, () -> repository.load("first")).getReason());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.append("first", "addition"));
        assertTrue(error.getCause() instanceof FileStoreException);
        assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
        assertEquals("original", first.getReconSummary());
    }

    @Test
    void fileListingsAndPreviewsCreateTheirOwnDirectories() throws Exception {
        AgentNodeSession session = session("files", "agent-a", "host-a");
        AtomicFileStore files = new AtomicFileStore();
        AgentSessionFileRepository repository = new AgentSessionFileRepository(files);

        File listing = repository.saveFileList(session.getSessionId(), "/var/log",
                Map.of("code", 200, "absolutePath", "/var/log", "count", 0, "fileList", List.of()));
        assertNotNull(listing);
        assertEquals("/var/log", files.readJsonMap(listing).get("absolutePath"));

        String previewPath = "/var/nested/preview.bin";
        File preview = repository.savePreview(session.getSessionId(), previewPath, new byte[]{1, 2});
        assertNotNull(preview);
        assertEquals(preview, repository.savePreview(session.getSessionId(), previewPath, new byte[]{3}));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(preview.toPath()));
    }

    @Test
    void webRuntimeSnapshotsKeepHostIsolationAndServerOwnedMetadata() {
        AgentNodeSession first = session("session-a", "agent-a", "host-a");
        AgentNodeSession second = session("session-b", "agent-a", "host-b");
        AgentWebRuntimeRepository repository = new AgentWebRuntimeRepository(new AtomicFileStore());

        Map<String, Object> input = Map.of("schemaVersion", 2, "runtime", "tomcat",
                "hostId", "caller-host", "saveTime", "caller-time");
        repository.save(first.getSessionId(), input);
        repository.save(second.getSessionId(), Map.of("schemaVersion", 2, "runtime", "jetty"));

        Map<String, Object> stored = repository.load(
                first.getCreateByUser(), first.resolveAgentId(), first.getCurrentHostId());
        assertEquals("tomcat", stored.get("runtime"));
        assertEquals("jetty", repository.load(second.getCreateByUser(), second.resolveAgentId(), second.getCurrentHostId()).get("runtime"));
        assertEquals("host-a", stored.get("hostId"));
        assertEquals("host-b", repository.load(second.getCreateByUser(), second.resolveAgentId(), second.getCurrentHostId()).get("hostId"));
        assertDoesNotThrow(() -> Instant.parse((String) stored.get("saveTime")));
        assertEquals("caller-time", input.get("saveTime"));
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

    @ParameterizedTest
    @CsvSource(value = {"null,''", "'',''", "'   ',''", "' user-a ',users/user-a"}, nullValues = "null")
    void workDirectoryCreationAndDeletionShareUserScopeWithoutRemovingSiblingData(
            String userId, String relativeRoot) throws Exception {
        Path base = vfsRoot.resolve(relativeRoot);
        AgentNodeSession session = session("scoped-session", "agent-a", "host-a");
        session.setCreateByUser(userId);
        Path agentDir = AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, " agent-a ").toPath();
        Path fileDir = AgentNodeSessionWorkDirUtil.getSessionFileDir(" " + session.getSessionId() + " ").toPath();
        assertEquals(base.resolve("agents/agent-a"), agentDir);
        assertEquals(base.resolve("sessions/scoped-session/file"), fileDir);
        Path agentData = Files.writeString(agentDir.resolve("retained.txt"), "agent data");
        Files.writeString(fileDir.resolve("cached.txt"), "session cache");
        Path otherUser = AgentNodeSessionWorkDirUtil.getAgentWorkDir("user-b", "agent-a").toPath();
        Path otherData = Files.writeString(otherUser.resolve("retained.txt"), "other user");

        Path workspaceData = null;
        if (userId == null || userId.isBlank()) {
            assertThrows(IllegalArgumentException.class, () -> AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId));
        } else {
            Path workspace = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath();
            assertEquals(base.resolve("workspace"), workspace);
            workspaceData = Files.writeString(workspace.resolve("personal.txt"), "personal data");
        }

        assertTrue(AgentNodeSessionWorkDirUtil.deleteSessionWorkDir(" scoped-session "));
        assertFalse(Files.exists(base.resolve("sessions/scoped-session")));
        assertTrue(AgentNodeSessionWorkDirUtil.deleteSessionWorkDir("scoped-session"));
        assertTrue(Files.exists(agentData));
        assertTrue(AgentNodeSessionWorkDirUtil.deleteAgentWorkDir(userId, " agent-a "));
        assertFalse(Files.exists(agentDir));
        assertTrue(AgentNodeSessionWorkDirUtil.deleteAgentWorkDir(userId, "agent-a"));
        assertEquals("other user", Files.readString(otherData));
        if (workspaceData != null) assertEquals("personal data", Files.readString(workspaceData));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workDirectoryDeletionRemovesLinksWithoutFollowingTheirTargets(boolean dangling) throws Exception {
        Path external = Files.createDirectory(vfsRoot.resolve("external"));
        Path retained = Files.writeString(external.resolve("keep.txt"), "external data");
        Path target = dangling ? vfsRoot.resolve("missing") : external;
        AgentNodeSession session = session("linked-session", "linked-agent", "host-a");
        Path fileDir = AgentNodeSessionWorkDirUtil.getSessionFileDir(session.getSessionId()).toPath();
        Files.createSymbolicLink(fileDir.resolve("linked"), target);

        assertTrue(AgentNodeSessionWorkDirUtil.deleteSessionWorkDir(session.getSessionId()));
        assertFalse(Files.exists(fileDir.getParent(), LinkOption.NOFOLLOW_LINKS));
        Path agentDir = AgentNodeSessionWorkDirUtil.getAgentWorkDir("user-a", "linked-agent").toPath();
        Files.delete(agentDir);
        Files.createSymbolicLink(agentDir, target);

        assertTrue(AgentNodeSessionWorkDirUtil.deleteAgentWorkDir("user-a", "linked-agent"));
        assertFalse(Files.exists(agentDir, LinkOption.NOFOLLOW_LINKS));
        assertEquals("external data", Files.readString(retained));
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

}
