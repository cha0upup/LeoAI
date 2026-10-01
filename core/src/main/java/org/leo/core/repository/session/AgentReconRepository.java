package org.leo.core.repository.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.file.Files;

/** Persistent agent-level reconnaissance summary storage. */
@Repository
public class AgentReconRepository {

    private static final String RECON_SUMMARY_MD = "recon-summary.md";
    private final AtomicFileStore fileStore;

    public AgentReconRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public synchronized File save(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            File file = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(
                    session.getCreateByUser(), session.resolveAgentId()), RECON_SUMMARY_MD);
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
            return null;
        }
    }

    public synchronized String append(String sessionId, String content) {
        String addition = normalize(content);
        if (sessionId == null || sessionId.isBlank() || addition == null) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            File file = new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(
                    session.getCreateByUser(), session.resolveAgentId()), RECON_SUMMARY_MD);
            String current = fileStore.readText(file);
            if (current == null) current = normalize(session.getReconSummary());
            String updated = current == null ? addition : current + "\n\n" + addition;
            fileStore.writeText(file, updated);
            sync(session, updated);
            return updated;
        } catch (Exception e) {
            return null;
        }
    }

    public String load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            AgentNodeSession session = requireSession(sessionId);
            return load(session.getCreateByUser(), session.resolveAgentId());
        } catch (Exception e) {
            return null;
        }
    }

    public String load(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            return fileStore.readText(new File(AgentNodeSessionWorkDirUtil.getAgentWorkDir(userId, agentId), RECON_SUMMARY_MD));
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

    private void sync(AgentNodeSession source, String content) {
        String agentId = source.resolveAgentId();
        String userId = source.getCreateByUser();
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session == null) continue;
            if (!java.util.Objects.equals(userId, session.getCreateByUser())) continue;
            if (!java.util.Objects.equals(agentId, session.resolveAgentId())) continue;
            session.setReconSummary(content);
        }
    }

    private String normalize(String content) {
        return content == null || content.isBlank() ? null : content.strip();
    }
}
