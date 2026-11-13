package org.leo.core.repository.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/** Persistent agent-level cache owned by HostId and discovery concerns. */
@Repository
public class AgentHostCacheRepository {

    private static final String BASIC_INFO_SUBDIR = "basic-info";
    private static final String HOST_DISCOVERY_JSON = "host-discovery.json";
    private static final String SAVE_TIME_KEY = "saveTime";
    private static final CacheSnapshot EMPTY_CACHE = new CacheSnapshot(List.of(), null);
    private final AtomicFileStore fileStore;

    public AgentHostCacheRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public File saveBasicInfo(String sessionId, String hostId, Map<String, Object> basicInfo) {
        if (sessionId == null || sessionId.isBlank() || hostId == null || hostId.isBlank() || basicInfo == null) {
            return null;
        }
        try {
            AgentNodeSession session = requireSession(sessionId);
            File hostDir = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(
                    session.getCreateByUser(), session.resolveAgentId()), BASIC_INFO_SUBDIR);
            Map<String, Object> root = new LinkedHashMap<>(basicInfo);
            root.put("hostId", hostId.trim());
            root.put(SAVE_TIME_KEY, Instant.now().toString());
            return fileStore.writeJson(new File(hostDir, encodeHostId(hostId.trim()) + ".json"), root);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> loadBasicInfo(String userId, String agentId, String hostId) {
        if (agentId == null || agentId.isBlank() || hostId == null || hostId.isBlank()) return null;
        try {
            File file = new File(new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), BASIC_INFO_SUBDIR),
                    encodeHostId(hostId.trim()) + ".json");
            return fileStore.readJsonMap(file);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> loadBasicInfo(String sessionId) {
        try {
            AgentNodeSession session = requireSession(sessionId);
            return loadBasicInfo(session.getCreateByUser(), session.resolveAgentId(), session.getCurrentHostId());
        } catch (Exception e) {
            return null;
        }
    }

    public CacheSnapshot readCache(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) return EMPTY_CACHE;
        try {
            File hostDir = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), BASIC_INFO_SUBDIR);
            File[] files = hostDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) return EMPTY_CACHE;
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            long newestModified = Long.MIN_VALUE;
            String saveTime = null;
            for (File file : files) {
                Map<String, Object> data = fileStore.readJsonMap(file);
                String id = text(data == null ? null : data.get("hostId"));
                if (id != null) ids.add(id);
                long modified = file.lastModified();
                if (file.length() > 0 && modified > newestModified) {
                    newestModified = modified;
                    saveTime = text(data == null ? null : data.get(SAVE_TIME_KEY));
                }
            }
            return new CacheSnapshot(List.copyOf(ids), saveTime);
        } catch (Exception e) {
            return EMPTY_CACHE;
        }
    }

    public record CacheSnapshot(List<String> hostIds, String saveTime) {}

    public void saveHostDiscovery(String userId, String agentId, String fingerprint, List<String> hostIds) {
        if (agentId == null || agentId.isBlank()) return;
        List<String> normalized = new ArrayList<>();
        if (hostIds != null) for (String id : hostIds) if (text(id) != null) normalized.add(text(id));
        if (normalized.isEmpty()) return;
        try {
            File file = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), HOST_DISCOVERY_JSON);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hostIds", List.copyOf(new LinkedHashSet<>(normalized)));
            data.put("discoveredAt", Instant.now().toString());
            data.put("fingerprint", fingerprint == null ? "" : fingerprint);
            fileStore.writeJson(file, data);
        } catch (Exception ignored) { }
    }

    public Map<String, Object> loadHostDiscovery(String userId, String agentId, String fingerprint) {
        try {
            File file = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), HOST_DISCOVERY_JSON);
            Map<String, Object> data = fileStore.readJsonMap(file);
            String expected = fingerprint == null ? "" : fingerprint;
            if (data == null || !expected.equals(String.valueOf(data.getOrDefault("fingerprint", "")))) return null;
            Object raw = data.get("hostIds");
            if (!(raw instanceof Collection<?>)) return null;
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            for (Object value : (Collection<?>) raw) if (text(value) != null) hosts.add(text(value));
            data.put("hostIds", List.copyOf(hosts));
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AgentNodeSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolveAgentId() == null) throw new IllegalStateException("session 未绑定 agentId");
        return session;
    }

    private String encodeHostId(String hostId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hostId.getBytes(StandardCharsets.UTF_8));
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
