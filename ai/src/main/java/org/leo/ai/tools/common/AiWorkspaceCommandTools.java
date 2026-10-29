package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolAccess;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.workspace.AiWorkspaceCommandService;
import org.leo.ai.service.workspace.AiWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 当前 Agent 任务工作空间中的直接命令执行工具。 */
@Component
@AiToolAccess(AiToolAccess.Level.ADMIN)
public class AiWorkspaceCommandTools {

    private final AiWorkspaceService workspaceService;
    private final AiWorkspaceCommandService commandService;

    public AiWorkspaceCommandTools(AiWorkspaceService workspaceService,
                                      AiWorkspaceCommandService commandService) {
        this.workspaceService = workspaceService;
        this.commandService = commandService;
    }

    @Tool("在当前 Agent 任务工作空间中异步执行本地命令。初始工作目录为任务 files 目录；默认超时120秒，最长600秒。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE,
            exclusive = true)
    public Map<String, Object> workspaceExec(
            @P("要执行的命令字符串，支持当前平台 Shell 语法") String command,
            @P(value = "超时秒数，默认120，最大600", required = false, defaultValue = "120") Integer timeoutSeconds) {
        return commandService.start(
                workspaceService.workspaceFromContext(), command, timeoutSeconds);
    }

    @Tool("查询当前 AI 工作空间命令的状态、输出尾部、日志路径和文件变更。")
    @AiToolPolicy(kind = AiToolKind.CONTEXT, operation = AiToolOperation.READ_ONLY,
            parallelizable = true)
    public Map<String, Object> workspaceExecStatus(
            @P("workspaceExec 返回的 runId") String runId) {
        return commandService.status(workspaceService.workspaceFromContext(), runId);
    }

    @Tool("终止当前 AI 工作空间中仍在运行的命令。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE,
            exclusive = true)
    public Map<String, Object> workspaceExecCancel(
            @P("workspaceExec 返回的 runId") String runId) {
        return commandService.cancel(workspaceService.workspaceFromContext(), runId);
    }
}
