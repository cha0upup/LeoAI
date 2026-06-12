package org.leo.web.controller.puppetnode.command;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.command.CommandExecRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/command")
public class CommandController {

    @PostMapping("/exec-command")
    public Map<String, Object> execCommand(@RequestBody CommandExecRequest request) {
        JavaPuppetNode javaNode = null;
        String cmd = request == null ? null : request.cmd();
        Map<String, Object> auditParams = auditParams(request);
        try {
            if (request == null) {
                throw ApiException.badRequest("请求体不能为空");
            }
            String sessionId = requireText(request.sessionId(), "sessionId");
            String type = requireCommandType(request.type());
            String processId = requireText(request.processId(), "processId");
            cmd = "write".equals(type) ? requireCommandPayload(request.cmd()) : normalizeCommand(request.cmd());

            javaNode = (JavaPuppetNode) ControllerUtil.getAbstractPuppetNode(sessionId);
            Map<String, Object> results = javaNode.execCommand(type, cmd, processId);
            AuditLogUtil.logSuccess(javaNode,
                    "COMMAND_EXEC", "执行命令", cmd, auditParams,
                    ApiResponse.CODE_SUCCESS, "执行命令成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results != null ? results : Collections.emptyMap());
        } catch (ApiException e) {
            AuditLogUtil.logFailure(javaNode,
                    "COMMAND_EXEC", "执行命令", cmd, auditParams,
                    e.getMessage(), AuditLogUtil.getClientIp());
            throw e;
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaNode,
                    "COMMAND_EXEC", "执行命令", cmd, auditParams,
                    e.getMessage(), AuditLogUtil.getClientIp());
            throw ApiException.serverError("执行命令失败: " + e.getMessage());
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + "不能为空");
        }
        return value.trim();
    }

    private String requireCommandType(String value) {
        String type = requireText(value, "type");
        if (!"write".equals(type) && !"read".equals(type) && !"stop".equals(type)) {
            throw ApiException.badRequest("type不支持");
        }
        return type;
    }

    private String requireCommandPayload(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest("cmd不能为空");
        }
        return value;
    }

    private String normalizeCommand(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> auditParams(CommandExecRequest request) {
        Map<String, Object> params = new HashMap<>();
        if (request == null) {
            return params;
        }
        params.put("sessionId", request.sessionId());
        params.put("cmd", request.cmd());
        params.put("type", request.type());
        params.put("processId", request.processId());
        return params;
    }
}
