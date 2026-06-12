package org.leo.web.controller.platform.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.SessionDtos.AppendReconSummaryRequest;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.dto.platform.session.SessionDtos.SetReconSummaryRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.service.ReconSummaryService;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 侦察摘要（Recon Summary）管理控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /platform/session/recon-summary}                    — 获取摘要</li>
 *   <li>{@code POST /platform/session/recon-summary/set}                — 覆盖写入</li>
 *   <li>{@code POST /platform/session/recon-summary/append}             — 追加内容</li>
 *   <li>{@code POST /platform/session/recon-summary/clear}              — 清空</li>
 *   <li>{@code POST /platform/session/recon-summary/organize}           — AI 整理</li>
 *   <li>{@code POST /platform/session/recon-summary/digest}             — 获取 digest 状态</li>
 *   <li>{@code POST /platform/session/recon-summary/digest/generate}    — 手动生成 digest</li>
 *   <li>{@code POST /platform/session/recon-summary/auto-append/toggle} — 切换自动追加开关</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/session")
public class ReconSummaryController {

    private final ReconSummaryService reconSummaryService;

    @Autowired
    public ReconSummaryController(ReconSummaryService reconSummaryService) {
        this.reconSummaryService = reconSummaryService;
    }

    @PostMapping("/recon-summary")
    public HashMap<String, Object> getReconSummary(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.load(sessionId, session));
    }

    @PostMapping("/recon-summary/set")
    public HashMap<String, Object> setReconSummary(@RequestBody SetReconSummaryRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.set(
                sessionId,
                session,
                request.reconSummary()));
    }

    @PostMapping("/recon-summary/append")
    public HashMap<String, Object> appendReconSummary(@RequestBody AppendReconSummaryRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        String content   = requireText(request.content(),   "content");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.append(sessionId, session, content));
    }

    @PostMapping("/recon-summary/clear")
    public HashMap<String, Object> clearReconSummary(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.clear(sessionId, session));
    }

    @PostMapping("/recon-summary/organize")
    public HashMap<String, Object> organizeReconSummary(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.organize(sessionId, session));
    }

    @PostMapping("/recon-summary/digest")
    public HashMap<String, Object> getReconSummaryDigest(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.digestStatus(sessionId, session));
    }

    @PostMapping("/recon-summary/digest/generate")
    public HashMap<String, Object> generateReconSummaryDigest(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.generateDigest(sessionId, session));
    }

    @PostMapping("/recon-summary/auto-append/toggle")
    public HashMap<String, Object> toggleAutoAppendRecon(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.toggleAutoAppend(sessionId, session));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        return value.trim();
    }
}
