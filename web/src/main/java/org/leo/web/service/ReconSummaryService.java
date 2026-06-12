package org.leo.web.service;

import org.leo.ai.service.ReconSummaryDigestService;
import org.leo.ai.service.ReconSummaryOrganizeService;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.dto.platform.session.SessionDtos.AutoAppendToggleResponse;
import org.leo.web.dto.platform.session.SessionDtos.DigestGenerateResponse;
import org.leo.web.dto.platform.session.SessionDtos.DigestResponse;
import org.leo.web.dto.platform.session.SessionDtos.OrganizeResponse;
import org.leo.web.dto.platform.session.SessionDtos.ReconSummaryMutateResponse;
import org.leo.web.dto.platform.session.SessionDtos.ReconSummaryResponse;
import org.leo.web.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 侦察摘要业务服务。
 *
 * <p>负责会话级 recon summary 的加载、持久化、结构化摘要合并、AI 整理、
 * digest 触发和开关切换等业务编排，供 Controller 做薄封装调用。
 */
@Service
public class ReconSummaryService {

    private final ReconSummaryOrganizeService reconSummaryOrganizeService;
    private final ReconSummaryDigestService reconSummaryDigestService;

    @Autowired
    public ReconSummaryService(ReconSummaryOrganizeService reconSummaryOrganizeService,
                               ReconSummaryDigestService reconSummaryDigestService) {
        this.reconSummaryOrganizeService = reconSummaryOrganizeService;
        this.reconSummaryDigestService = reconSummaryDigestService;
    }

    public ReconSummaryResponse load(String sessionId, PuppetNodeSession session) {
        String persisted = PuppetNodeSessionWorkDirUtil.loadReconSummary(sessionId);
        if (persisted != null) session.setReconSummary(persisted);

        return new ReconSummaryResponse(
                sessionId,
                session.getReconSummary(),
                session.hasReconSummary());
    }

    public ReconSummaryMutateResponse set(String sessionId,
                                          PuppetNodeSession session,
                                          String summary) {
        session.setReconSummary(summary == null || summary.isBlank() ? null : summary);
        PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, session.getReconSummary());
        triggerDigestIfNeeded(session);
        return new ReconSummaryMutateResponse(sessionId, session.getReconSummary(), "侦察摘要已更新");
    }

    public ReconSummaryMutateResponse append(String sessionId, PuppetNodeSession session, String content) {
        String updated = PuppetNodeSessionWorkDirUtil.appendReconSummary(sessionId, content);
        if (updated == null) {
            session.appendReconSummary(content);
            updated = session.getReconSummary();
            PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, updated);
        } else {
            session.setReconSummary(updated);
        }
        triggerDigestIfNeeded(session);
        return new ReconSummaryMutateResponse(sessionId, updated, "侦察摘要已追加");
    }

    public ReconSummaryMutateResponse clear(String sessionId, PuppetNodeSession session) {
        session.setReconSummary(null);
        PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, null);
        return new ReconSummaryMutateResponse(sessionId, null, "侦察摘要已清空");
    }

    public OrganizeResponse organize(String sessionId, PuppetNodeSession session) {
        if (!session.hasReconSummary()) {
            throw ApiException.badRequest("侦察摘要为空，无需整理");
        }
        String organized = reconSummaryOrganizeService.organize(session.getReconSummary());
        session.setReconSummary(organized);
        PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, organized);
        if (organized.length() >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
            reconSummaryDigestService.generateAndSaveAsync(session);
        }
        return new OrganizeResponse(sessionId, organized, "侦察摘要已整理并保存");
    }

    public DigestResponse digestStatus(String sessionId, PuppetNodeSession session) {
        return new DigestResponse(
                sessionId,
                session.getReconSummaryDigest(),
                session.isReconSummaryDigestDirty(),
                session.hasFreshReconSummaryDigest(),
                session.hasReconSummary() ? session.getReconSummary().length() : 0,
                ReconSummaryDigestService.DIGEST_THRESHOLD);
    }

    public DigestGenerateResponse generateDigest(String sessionId, PuppetNodeSession session) {
        // 空摘要不视为错误：前端 AI turn 结束后会无差别调用此接口，若主 Agent 这一轮没有
        // 生成任何侦察数据，应该静默跳过而不是抛 ApiException —— 否则 GlobalExceptionHandler
        // 会打 WARN 日志，制造噪音。返回 digest=null + 友好提示即可，前端拿到后自行决定是否更新 UI。
        if (!session.hasReconSummary()) {
            return new DigestGenerateResponse(sessionId, null, "尚无侦察摘要，跳过精简版生成");
        }
        String digest = reconSummaryDigestService.generateAndSave(session);
        return new DigestGenerateResponse(sessionId, digest, "精简版已生成");
    }

    public AutoAppendToggleResponse toggleAutoAppend(String sessionId, PuppetNodeSession session) {
        boolean newVal = !session.isAutoAppendRecon();
        session.setAutoAppendRecon(newVal);
        return new AutoAppendToggleResponse(sessionId, newVal, newVal ? "自动追加已开启" : "自动追加已关闭");
    }

    private void triggerDigestIfNeeded(PuppetNodeSession session) {
        if (session.hasReconSummary()
                && session.getReconSummary().length() >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
            reconSummaryDigestService.generateAndSaveAsync(session);
        }
    }
}
