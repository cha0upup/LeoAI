package org.leo.web.controller.platform.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.SessionDtos.ReportResponse;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.service.SessionReportService;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 会话侦察报告控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /platform/session/report/generate} — 生成离线侦察报告（Markdown）</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/session")
public class SessionReportController {

    private final SessionReportService sessionReportService;

    @Autowired
    public SessionReportController(SessionReportService sessionReportService) {
        this.sessionReportService = sessionReportService;
    }

    /**
     * 生成离线侦察报告（Markdown 格式）。
     *
     * <p>汇聚 puppet 级持久化目录中的 basic-info.json、recon-summary.md、
     * recon-summary.json，拼装为结构化 Markdown 字符串返回给前端，前端负责触发下载。
     */
    @PostMapping("/report/generate")
    public HashMap<String, Object> generateReport(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        ReportResponse report = sessionReportService.generate(session);
        return ApiResponse.success(report);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        return value.trim();
    }
}
