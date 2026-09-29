package org.leo.core.repository.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;

import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetRepositoryTest {

    @TempDir
    Path vfsRoot;

    private String previousVfsPath;

    @BeforeEach
    void setUp() throws Exception {
        previousVfsPath = setVfsPath(vfsRoot.toString());
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @AfterEach
    void tearDown() throws Exception {
        PuppetNodeSessionContainer.clearAllSessions();
        setVfsPath(previousVfsPath);
    }

    @Test
    void hostCacheIsIsolatedByHostIdAndDiscoveryUsesFingerprint() {
        PuppetNodeSession session = session("session-a", "puppet-a", "host-a");
        PuppetHostCacheRepository repository = new PuppetHostCacheRepository(new AtomicFileStore());

        repository.saveBasicInfo(session.getSessionId(), "host-a", Map.of("hostname", "alpha"));
        repository.saveBasicInfo(session.getSessionId(), "host-b", Map.of("hostname", "beta"));

        assertEquals("alpha", repository.loadBasicInfo("user-a", "puppet-a", "host-a").get("hostname"));
        assertEquals("beta", repository.loadBasicInfo("user-a", "puppet-a", "host-b").get("hostname"));
        assertEquals(List.of("host-a", "host-b"), repository.listCachedHostIds("user-a", "puppet-a"));

        repository.saveHostDiscovery("user-a", "puppet-a", "fingerprint-1",
                List.of("host-a", "host-a", "host-b"));
        assertEquals(List.of("host-a", "host-b"), repository.loadHostDiscovery(
                "user-a", "puppet-a", "fingerprint-1").get("hostIds"));
        assertNull(repository.loadHostDiscovery("user-a", "puppet-a", "fingerprint-2"));
    }

    @Test
    void reconRepositoryPersistsAndSynchronizesAllSessionsForThePuppet() {
        PuppetNodeSession first = session("session-a", "puppet-a", "host-a");
        PuppetNodeSession second = session("session-b", "puppet-a", "host-b");
        PuppetNodeSession other = session("session-c", "puppet-b", "host-c");
        PuppetReconRepository repository = new PuppetReconRepository(new AtomicFileStore());

        assertEquals("first", repository.append(first.getSessionId(), "first"));
        assertEquals("first\n\nsecond", repository.append(first.getSessionId(), "second"));
        assertEquals("first\n\nsecond", second.getReconSummary());
        assertNull(other.getReconSummary());
        assertEquals("first\n\nsecond", repository.load("user-a", "puppet-a"));

        repository.save(first.getSessionId(), "replacement");
        assertEquals("replacement", first.getReconSummary());
        assertEquals("replacement", second.getReconSummary());
        assertNull(repository.load("user-a", "puppet-b"));
    }

    @Test
    void webRuntimeSnapshotsAreIsolatedByHostId() {
        PuppetNodeSession first = session("session-a", "puppet-a", "host-a");
        PuppetNodeSession second = session("session-b", "puppet-a", "host-b");
        PuppetWebRuntimeRepository repository = new PuppetWebRuntimeRepository(new AtomicFileStore());

        repository.save(first.getSessionId(), Map.of("schemaVersion", 2, "runtime", "tomcat"));
        repository.save(second.getSessionId(), Map.of("schemaVersion", 2, "runtime", "jetty"));

        assertEquals("tomcat", repository.load(first.getSessionId()).get("runtime"));
        assertEquals("jetty", repository.load(second.getSessionId()).get("runtime"));
        assertEquals("host-a", repository.load(first.getSessionId()).get("hostId"));
        assertEquals("host-b", repository.load(second.getSessionId()).get("hostId"));
    }

    @Test
    void webRuntimeRepositoryRejectsOldSnapshotVersions() throws Exception {
        PuppetNodeSession current = session("session-old", "puppet-old", "host-old");
        AtomicFileStore files = new AtomicFileStore();
        PuppetWebRuntimeRepository repository = new PuppetWebRuntimeRepository(files);

        assertNull(repository.save(current.getSessionId(), Map.of("schemaVersion", 1, "runtime", "old")));
        assertNull(repository.save(current.getSessionId(), Map.of("schemaVersion", 2.5, "runtime", "old")));
        File file = repository.save(current.getSessionId(), Map.of("schemaVersion", 2, "runtime", "current"));
        assertEquals("current", repository.load(current.getSessionId()).get("runtime"));

        files.writeJson(file, Map.of("schemaVersion", 1, "runtime", "old"));
        assertNull(repository.load(current.getSessionId()));
    }

    private PuppetNodeSession session(String sessionId, String puppetId, String hostId) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(sessionId);
        session.setCreateByUser("user-a");
        session.setPuppetId(puppetId);
        session.bindHostId(hostId);
        PuppetNodeSessionContainer.addSession(sessionId, session);
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
