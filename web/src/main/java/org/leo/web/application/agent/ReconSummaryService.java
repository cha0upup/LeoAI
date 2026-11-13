package org.leo.web.application.agent;

import org.leo.ai.service.ReconSummaryDigestService;
import org.leo.ai.service.ReconSummaryOrganizeService;
import org.leo.core.infrastructure.persistence.session.AgentReconRepository;
import org.leo.core.session.AgentNodeSession;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.UseCaseException.Kind;
import org.springframework.stereotype.Service;

/**
 * 侦察摘要业务服务。
 *
 * <p>负责会话级 recon summary 的加载、持久化、结构化摘要合并、AI 整理、
 * digest 触发和开关切换等业务编排，供 Controller 做薄封装调用。
 */
@Service
public class ReconSummaryService {

    public record Summary(String sessionId, String reconSummary, boolean hasReconSummary) {}

    public record SummaryUpdate(String sessionId, String reconSummary, String msg) {}

    public record Digest(String sessionId, String digest, boolean dirty, boolean fresh,
                         int summaryLength, int threshold) {}

    public record GeneratedDigest(String sessionId, String digest, String msg) {}

    public record AutoAppendState(String sessionId, boolean autoAppendRecon, String msg) {}

    private final ReconSummaryOrganizeService reconSummaryOrganizeService;
    private final ReconSummaryDigestService reconSummaryDigestService;
    private final AgentReconRepository repository;

    public ReconSummaryService(ReconSummaryOrganizeService reconSummaryOrganizeService,
                               ReconSummaryDigestService reconSummaryDigestService,
                               AgentReconRepository repository) {
        this.reconSummaryOrganizeService = reconSummaryOrganizeService;
        this.reconSummaryDigestService = reconSummaryDigestService;
        this.repository = repository;
    }

    public Summary load(String sessionId, AgentNodeSession session) {
        String persisted = repository.load(sessionId);
        session.setReconSummary(persisted);

        return new Summary(
                sessionId,
                session.getReconSummary(),
                session.hasReconSummary());
    }

    public SummaryUpdate set(String sessionId,
                                          AgentNodeSession session,
                                          String summary) {
        repository.save(sessionId, summary);
        triggerDigestIfNeeded(session);
        return new SummaryUpdate(sessionId, session.getReconSummary(), "侦察摘要已更新");
    }

    public SummaryUpdate append(String sessionId, AgentNodeSession session, String content) {
        if (content == null || content.isBlank()) throw new UseCaseException(Kind.INVALID_INPUT, "追加内容为空");
        String updated = repository.append(sessionId, content);
        if (updated == null) throw new IllegalStateException("追加侦察摘要失败");
        triggerDigestIfNeeded(session);
        return new SummaryUpdate(sessionId, updated, "侦察摘要已追加");
    }

    public SummaryUpdate clear(String sessionId, AgentNodeSession session) {
        repository.save(sessionId, null);
        return new SummaryUpdate(sessionId, null, "侦察摘要已清空");
    }

    public SummaryUpdate organize(String sessionId, AgentNodeSession session) {
        if (!session.hasReconSummary()) {
            throw new UseCaseException(Kind.INVALID_INPUT, "侦察摘要为空，无需整理");
        }
        String organized = reconSummaryOrganizeService.organize(session.getReconSummary());
        repository.save(sessionId, organized);
        triggerDigestIfNeeded(session);
        return new SummaryUpdate(sessionId, organized, "侦察摘要已整理并保存");
    }

    public Digest digestStatus(String sessionId, AgentNodeSession session) {
        return new Digest(
                sessionId,
                session.getReconSummaryDigest(),
                session.isReconSummaryDigestDirty(),
                session.hasFreshReconSummaryDigest(),
                session.hasReconSummary() ? session.getReconSummary().length() : 0,
                ReconSummaryDigestService.DIGEST_THRESHOLD);
    }

    public GeneratedDigest generateDigest(String sessionId, AgentNodeSession session) {
        // 空摘要不视为错误：前端 AI turn 结束后会无差别调用此接口，若主 Agent 这一轮没有
        // 生成任何侦察数据，应该静默跳过而不是抛 UseCaseException —— 否则 GlobalExceptionHandler
        // 会打 WARN 日志，制造噪音。返回 digest=null + 友好提示即可，前端拿到后自行决定是否更新 UI。
        if (!session.hasReconSummary()) {
            return new GeneratedDigest(sessionId, null, "尚无侦察摘要，跳过精简版生成");
        }
        String digest = reconSummaryDigestService.generateAndSave(session);
        return new GeneratedDigest(sessionId, digest,
                digest != null ? "精简版已生成" : "摘要已更新，跳过过期精简结果");
    }

    public AutoAppendState toggleAutoAppend(String sessionId, AgentNodeSession session) {
        boolean newVal = !session.isAutoAppendRecon();
        session.setAutoAppendRecon(newVal);
        return new AutoAppendState(sessionId, newVal, newVal ? "自动追加已开启" : "自动追加已关闭");
    }

    private void triggerDigestIfNeeded(AgentNodeSession session) {
        if (session.hasReconSummary()
                && session.getReconSummary().length() >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
            reconSummaryDigestService.generateAndSaveAsync(session);
        }
    }
}
