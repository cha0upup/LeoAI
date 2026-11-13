package org.leo.web.controller.platform.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.application.agent.ReconSummaryService;
import org.leo.web.dto.platform.session.SessionDtos.AppendReconSummaryRequest;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.dto.platform.session.SessionDtos.SetReconSummaryRequest;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/** 会话摘要管理 HTTP 接口。 */
@RestController
@RequestMapping("/platform/session")
public class ReconSummaryController {

    private final ReconSummaryService reconSummaryService;

    public ReconSummaryController(ReconSummaryService reconSummaryService) {
        this.reconSummaryService = reconSummaryService;
    }

    @PostMapping("/recon-summary")
    public HashMap<String, Object> getReconSummary(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.load(sessionId, session));
    }

    @PostMapping("/recon-summary/set")
    public HashMap<String, Object> setReconSummary(@RequestBody SetReconSummaryRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.set(
                sessionId,
                session,
                request.reconSummary()));
    }

    @PostMapping("/recon-summary/append")
    public HashMap<String, Object> appendReconSummary(@RequestBody AppendReconSummaryRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        String content   = ControllerUtil.requireText(request.content(), "content 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.append(sessionId, session, content));
    }

    @PostMapping("/recon-summary/clear")
    public HashMap<String, Object> clearReconSummary(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.clear(sessionId, session));
    }

    @PostMapping("/recon-summary/organize")
    public HashMap<String, Object> organizeReconSummary(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.organize(sessionId, session));
    }

    @PostMapping("/recon-summary/digest")
    public HashMap<String, Object> getReconSummaryDigest(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.digestStatus(sessionId, session));
    }

    @PostMapping("/recon-summary/digest/generate")
    public HashMap<String, Object> generateReconSummaryDigest(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.generateDigest(sessionId, session));
    }

    @PostMapping("/recon-summary/auto-append/toggle")
    public HashMap<String, Object> toggleAutoAppendRecon(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(reconSummaryService.toggleAutoAppend(sessionId, session));
    }
}
