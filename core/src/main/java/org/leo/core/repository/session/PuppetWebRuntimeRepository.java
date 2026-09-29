package org.leo.core.repository.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent Web Runtime snapshots isolated by Puppet and HostId. */
@Repository
public class PuppetWebRuntimeRepository {

    private static final String WEB_RUNTIME_SUBDIR = "web-runtime";
    private static final String SNAPSHOT_JSON = "snapshot.json";
    private static final int SCHEMA_VERSION = 2;
    private final AtomicFileStore fileStore;

    public PuppetWebRuntimeRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public File save(String sessionId, Map<String, Object> snapshot) {
        if (sessionId == null || sessionId.isBlank() || !isCurrentSnapshot(snapshot)) return null;
        try {
            PuppetNodeSession session = requireSession(sessionId);
            String hostId = session.getCurrentHostId();
            if (hostId == null || hostId.isBlank()) return null;
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("saveTime", Instant.now().toString());
            structured.putAll(snapshot);
            structured.put("hostId", hostId.trim());
            File dir = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(
                    session.getCreateByUser(), session.resolvePuppetId()), WEB_RUNTIME_SUBDIR);
            return fileStore.writeJson(new File(dir, encodeHostId(hostId) + "." + SNAPSHOT_JSON), structured);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> load(String userId, String puppetId, String hostId) {
        if (puppetId == null || puppetId.isBlank() || hostId == null || hostId.isBlank()) return null;
        try {
            File dir = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), WEB_RUNTIME_SUBDIR);
            Map<String, Object> snapshot = fileStore.readJsonMap(
                    new File(dir, encodeHostId(hostId) + "." + SNAPSHOT_JSON));
            return isCurrentSnapshot(snapshot) ? snapshot : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> load(String sessionId) {
        try {
            PuppetNodeSession session = requireSession(sessionId);
            return load(session.getCreateByUser(), session.resolvePuppetId(), session.getCurrentHostId());
        } catch (Exception e) {
            return null;
        }
    }

    private PuppetNodeSession requireSession(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolvePuppetId() == null) {
            throw new IllegalStateException("session 未绑定 puppetId: " + sessionId);
        }
        return session;
    }

    private String encodeHostId(String hostId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hostId.trim().getBytes(StandardCharsets.UTF_8));
    }

    private boolean isCurrentSnapshot(Map<String, Object> snapshot) {
        return snapshot != null && Integer.valueOf(SCHEMA_VERSION).equals(snapshot.get("schemaVersion"));
    }
}
