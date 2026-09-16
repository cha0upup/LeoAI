package org.leo.web.controller.puppetnode.command;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.command.CommandExecRequest;
import org.leo.web.dto.puppetnode.command.TerminalBatchReadRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/command")
public class CommandController {

    @PostMapping("/read-batch")
    public Map<String, Object> readBatch(@RequestBody TerminalBatchReadRequest request) {
        request = TerminalBatchReadRequest.normalize(request);
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(request.sessionId());
        TerminalCapable node = ControllerUtil.requireCapability(session, TerminalCapable.class);
        session.touchLastActiveTime();
        try {
            Map<String, Object> result = requireSuccessfulResult(node.readTerminals(request.processIds()));
            if (!(result.get("terminals") instanceof Map<?, ?>)) {
                throw ApiException.serverError("批量终端读取返回了无效结果");
            }
            return ApiResponse.success(result);
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw ApiException.serverError("读取终端失败: " + error.getMessage());
        }
    }

    @PostMapping("/exec-command")
    public Map<String, Object> execCommand(@RequestBody CommandExecRequest request) {
        AbstractPuppetNode auditNode = null;
        String cmd = request == null ? null : request.cmd();
        Map<String, Object> auditParams = auditParams(request);
        try {
            request = CommandExecRequest.normalize(request);
            auditParams = auditParams(request);
            String sessionId = request.sessionId();
            String type = request.type();
            String processId = request.processId();
            cmd = request.cmd();

            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
            TerminalCapable commandNode = ControllerUtil.requireCapability(session, TerminalCapable.class);
            session.touchLastActiveTime();
            if (commandNode instanceof AbstractPuppetNode node) {
                auditNode = node;
            }
            Map<String, Object> results = commandNode.execTerminal(
                    type, cmd, processId, request.terminalMode(), request.includeOutput());
            requireSuccessfulResult(results);
            logCommandAudit(auditNode, type, cmd, processId, auditParams, null);
            return ApiResponse.success(results);
        } catch (ApiException e) {
            logCommandAudit(auditNode, request == null ? null : request.type(), cmd,
                    request == null ? null : request.processId(), auditParams, e.getMessage());
            throw e;
        } catch (Exception e) {
            logCommandAudit(auditNode, request == null ? null : request.type(), cmd,
                    request == null ? null : request.processId(), auditParams, e.getMessage());
            throw ApiException.serverError("执行命令失败: " + e.getMessage());
        }
    }

    private Map<String, Object> requireSuccessfulResult(Map<String, Object> result) {
        if (result == null || !(result.get("code") instanceof Number code)
                || code.intValue() != ApiResponse.CODE_SUCCESS) {
            Object message = result == null ? null : result.get("msg");
            throw ApiException.serverError(message == null
                    ? "终端操作失败：目标返回了无效结果" : String.valueOf(message));
        }
        return result;
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
        if (request.terminalMode() != null) params.put("terminalMode", request.terminalMode());
        return params;
    }

    private void logCommandAudit(AbstractPuppetNode node,
                                 String type,
                                 String cmd,
                                 String processId,
                                 Map<String, Object> auditParams,
                                 String errorMessage) {
        if (node == null || "read".equals(type) || "resize".equals(type)) return;
        boolean stop = "stop".equals(type);
        boolean init = "init".equals(type);
        String operation = stop ? "COMMAND_STOP" : init ? "COMMAND_INIT" : "COMMAND_EXEC";
        String description = stop ? "停止命令进程" : init ? "初始化终端" : "执行命令";
        String target = stop || init ? processId : cmd;
        if (errorMessage == null) {
            AuditLogUtil.logSuccess(node, operation, description, target, auditParams,
                    ApiResponse.CODE_SUCCESS, description + "成功", AuditLogUtil.getClientIp());
        } else {
            AuditLogUtil.logFailure(node, operation, description, target, auditParams,
                    errorMessage, AuditLogUtil.getClientIp());
        }
    }
}
