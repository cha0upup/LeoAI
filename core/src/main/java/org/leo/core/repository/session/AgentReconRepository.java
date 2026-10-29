package org.leo.core.repository.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Persistent reconnaissance summaries isolated by user, Agent and HostId. */
@Repository
public class AgentReconRepository {

    private final AtomicFileStore fileStore;

    public AgentReconRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public synchronized File save(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            File file = summaryFile(session.getCreateByUser(), session.resolveAgentId(), session.getCurrentHostId());
            String normalized = normalize(content);
            if (normalized == null) {
                Files.deleteIfExists(file.toPath());
                sync(session, null);
                return null;
            }
            File saved = fileStore.writeText(file, normalized);
            sync(session, normalized);
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("保存侦察摘要失败", e);
        }
    }

    public synchronized String append(String sessionId, String content) {
        String addition = normalize(content);
        if (sessionId == null || sessionId.isBlank() || addition == null) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            File file = summaryFile(session.getCreateByUser(), session.resolveAgentId(), session.getCurrentHostId());
            String current = normalize(fileStore.readText(file));
            String updated = current == null ? addition : current + "\n\n" + addition;
            fileStore.writeText(file, updated);
            sync(session, updated);
            return updated;
        } catch (Exception e) {
            throw new IllegalStateException("追加侦察摘要失败", e);
        }
    }

    public String load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        return session == null ? null : load(session.getCreateByUser(), session.resolveAgentId(), session.getCurrentHostId());
    }

    public String load(String userId, String agentId, String hostId) {
        if (userId == null || userId.isBlank() || agentId == null || agentId.isBlank()
                || hostId == null || hostId.isBlank()) return null;
        return normalize(fileStore.readText(summaryFile(userId, agentId, hostId)));
    }

    private File summaryFile(String userId, String agentId, String hostId) {
        String encodedHostId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hostId.getBytes(StandardCharsets.UTF_8));
        File dir = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), "recon-summary");
        return new File(dir, encodedHostId + ".md");
    }

    private AgentNodeSession requireSession(String sessionId) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolveAgentId() == null
                || session.getCreateByUser() == null || session.getCreateByUser().isBlank()
                || session.getCurrentHostId() == null || session.getCurrentHostId().isBlank()) {
            throw new IllegalStateException("session 缺少用户、Agent 或 HostId: " + sessionId);
        }
        return session;
    }

    private void sync(AgentNodeSession source, String content) {
        String agentId = source.resolveAgentId();
        String userId = source.getCreateByUser();
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session == null) continue;
            if (!java.util.Objects.equals(userId, session.getCreateByUser())) continue;
            if (!java.util.Objects.equals(agentId, session.resolveAgentId())) continue;
            if (!java.util.Objects.equals(source.getCurrentHostId(), session.getCurrentHostId())) continue;
            session.setReconSummary(content);
        }
    }

    private String normalize(String content) {
        return content == null || content.isBlank() ? null : content.strip();
    }
}
