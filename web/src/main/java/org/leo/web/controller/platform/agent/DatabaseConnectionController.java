package org.leo.web.controller.platform.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.service.DatabaseConnectionManagementService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** HTTP boundary for persisted database connection profile management. */
@RestController
@RequestMapping("/platform/agent-manage")
public final class DatabaseConnectionController {

    private final DatabaseConnectionManagementService managementService;

    public DatabaseConnectionController(DatabaseConnectionManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/database-connections")
    public HashMap<String, Object> save(HttpServletRequest request,
                                        @RequestBody HashMap<String, Object> params) {
        User user = requireUser(request);
        return ApiResponse.success(managementService.save(user, requireAgentId(params), params));
    }

    @PostMapping("/database-connections/list")
    public HashMap<String, Object> list(HttpServletRequest request,
                                        @RequestBody HashMap<String, Object> params) {
        return ApiResponse.success(managementService.listByAgent(
                requireAgentId(params), requireUser(request)));
    }

    @PostMapping("/database-connections/delete")
    public HashMap<String, Object> delete(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        managementService.delete(connectionId(params), requireAgentId(params), requireUser(request));
        return ApiResponse.success();
    }

    @PostMapping("/database-connections/status")
    public HashMap<String, Object> updateStatus(HttpServletRequest request,
                                                @RequestBody HashMap<String, Object> params) {
        Object enabled = params == null ? null : params.get("enabled");
        if (!(enabled instanceof Boolean value)) {
            throw ApiException.badRequest("enabled必须是布尔值");
        }
        Map<String, Object> result = managementService.setEnabled(
                connectionId(params), requireAgentId(params), value, requireUser(request));
        return ApiResponse.success(result);
    }

    private User requireUser(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user;
    }

    private String requireAgentId(Map<String, Object> params) {
        AbstractAgentNode node = ControllerUtil.getAbstractAgentNode(params);
        if (node.getAgent() == null || node.getAgent().getAgentId() == null
                || node.getAgent().getAgentId().isBlank()) {
            throw ApiException.badRequest("无法从会话中获取Agent ID");
        }
        return node.getAgent().getAgentId();
    }

    private String connectionId(Map<String, Object> params) {
        if (params == null) throw ApiException.badRequest("params不能为空");
        return text(params.get("connectionId"));
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
