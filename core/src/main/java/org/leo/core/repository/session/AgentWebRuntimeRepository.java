package org.leo.core.repository.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent Web Runtime snapshots isolated by Agent and HostId. */
@Repository
public class AgentWebRuntimeRepository {

    private static final String WEB_RUNTIME_SUBDIR = "web-runtime";
    private static final String SNAPSHOT_JSON = "snapshot.json";
    private static final int SCHEMA_VERSION = 2;
    private final AtomicFileStore fileStore;

    public AgentWebRuntimeRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public File save(String sessionId, Map<String, Object> snapshot) {
        if (sessionId == null || sessionId.isBlank() || !isCurrentSnapshot(snapshot)) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            String hostId = session.getCurrentHostId();
            if (hostId == null || hostId.isBlank()) return null;
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("saveTime", Instant.now().toString());
            structured.putAll(snapshot);
            structured.put("hostId", hostId.trim());
            File dir = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(
                    session.getCreateByUser(), session.resolveAgentId()), WEB_RUNTIME_SUBDIR);
            return fileStore.writeJson(new File(dir, encodeHostId(hostId) + "." + SNAPSHOT_JSON), structured);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> load(String userId, String agentId, String hostId) {
        if (agentId == null || agentId.isBlank() || hostId == null || hostId.isBlank()) return null;
        try {
            File dir = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), WEB_RUNTIME_SUBDIR);
            Map<String, Object> snapshot = fileStore.readJsonMap(
                    new File(dir, encodeHostId(hostId) + "." + SNAPSHOT_JSON));
            return isCurrentSnapshot(snapshot) ? snapshot : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AgentNodeSession requireSession(String sessionId) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolveAgentId() == null) {
            throw new IllegalStateException("session 未绑定 agentId: " + sessionId);
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
