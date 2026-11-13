package org.leo.web.controller.platform.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.service.sql.DatabaseConnectionProfileService;
import org.leo.web.exception.ApiException;
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

    private final DatabaseConnectionProfileService profiles;

    public DatabaseConnectionController(DatabaseConnectionProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/database-connections")
    public HashMap<String, Object> save(HttpServletRequest request,
                                        @RequestBody HashMap<String, Object> params) {
        String userId = requireUserId(request);
        return ApiResponse.success(profiles.save(userId, requireAgentId(params), params));
    }

    @PostMapping("/database-connections/list")
    public HashMap<String, Object> list(HttpServletRequest request,
                                        @RequestBody HashMap<String, Object> params) {
        String userId = requireUserId(request);
        return ApiResponse.success(profiles.listByAgent(userId, requireAgentId(params)));
    }

    @PostMapping("/database-connections/delete")
    public HashMap<String, Object> delete(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        String userId = requireUserId(request);
        profiles.delete(userId, requireAgentId(params), connectionId(params));
        return ApiResponse.success();
    }

    @PostMapping("/database-connections/status")
    public HashMap<String, Object> updateStatus(HttpServletRequest request,
                                                @RequestBody HashMap<String, Object> params) {
        String userId = requireUserId(request);
        Object enabled = params == null ? null : params.get("enabled");
        if (!(enabled instanceof Boolean value)) {
            throw ApiException.badRequest("enabled必须是布尔值");
        }
        Map<String, Object> result = profiles.setEnabled(
                userId, requireAgentId(params), connectionId(params), value);
        return ApiResponse.success(result);
    }

    private String requireUserId(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user.getUserId();
    }

    private String requireAgentId(Map<String, Object> params) {
        var agent = ControllerUtil.getAbstractAgentNode(params).getAgent();
        if (agent == null || agent.getAgentId() == null || agent.getAgentId().isBlank()) {
            throw ApiException.badRequest("无法从会话中获取Agent ID");
        }
        return agent.getAgentId();
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
