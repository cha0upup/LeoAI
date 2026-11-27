package org.leo.web.controller.platform.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.application.agent.SessionReportService;
import org.leo.web.dto.SessionIdRequest;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/** 会话报告 HTTP 接口。 */
@RestController
@RequestMapping("/platform/session")
public class SessionReportController {

    private final SessionReportService sessionReportService;

    public SessionReportController(SessionReportService sessionReportService) {
        this.sessionReportService = sessionReportService;
    }

    /**
     * 生成离线侦察报告（Markdown 格式）。
     *
     * <p>获取已授权会话对应用户、Agent、HostId 的报告，由前端触发下载。
     */
    @PostMapping("/report/generate")
    public HashMap<String, Object> generateReport(@RequestBody SessionIdRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(sessionReportService.generate(session));
    }
}
