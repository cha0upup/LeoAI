package org.leo.ai.tools.agentnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiUserInputRepository;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.ssh.SshConnectionException;
import org.leo.service.ssh.SshConnectionOperations;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** SSH tools use the authenticated AI actor, not the original owner of a shared node session. */
@Component
@AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE)
public final class SshConnectionTools {
    private final SshConnectionOperations connections;
    private final AiUserInputRepository requests;

    public SshConnectionTools(SshConnectionOperations connections, AiUserInputRepository requests) {
        this.connections = connections;
        this.requests = requests;
    }

    @Tool("列出当前 Agent 已保存的 SSH 连接配置。返回 profileId、version、主机、认证类型及凭据存在标记，不返回密码、私钥或口令。")
    @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> listSshConnections() {
        Scope scope = scope();
        return invoke(() -> connections.list(scope.userId(), scope.sessionId()));
    }

    @Tool("读取当前 Agent 的单个 SSH 配置详情，不返回凭据。编辑、删除或测试前用返回的最新 version 防止覆盖并发修改。")
    @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> getSshConnection(@P("listSshConnections 返回的 profileId") String profileId) {
        Scope scope = scope();
        return invoke(() -> connections.get(scope.userId(), scope.sessionId(), profileId));
    }

    @Tool("""
            为当前 Agent 新增 SSH 配置，和 SSH 面板共享。config 支持 name、host、port（默认22）、username、
            authType（password 或 privateKey，默认password）、password、privateKey（PEM正文）、passphrase。
            host、username 和对应认证凭据必填。凭据加密存储，响应只返回元数据；保存不会自动连接或信任主机指纹。
            """)
    public Map<String, Object> createSshConnection(@P("SSH 配置字段对象") Map<String, Object> config) {
        Scope scope = scope();
        return invoke(() -> connections.create(scope.userId(), scope.sessionId(), config));
    }

    @Tool("""
            修改当前 Agent 已保存的 SSH 配置。profileId、version 使用最近一次列表或详情返回值。
            patch 仅传需修改的 name、host、port、username、authType、password、privateKey、passphrase。
            未传字段保持原值，省略或 null 凭据保留原值，passphrase="" 清空私钥口令。
            切换认证类型须提供对应凭据；主机或端口变化会清除已信任指纹。响应不返回凭据。
            """)
    public Map<String, Object> updateSshConnection(
            @P("已保存的 profileId") String profileId,
            @P("最新配置版本号") Long version,
            @P("仅包含待修改字段的对象") Map<String, Object> patch) {
        Scope scope = scope();
        return invoke(() -> connections.update(scope.userId(), scope.sessionId(), profileId, version, patch));
    }

    @Tool("删除当前 Agent 的 SSH 配置。先核对 profileId 和最新 version；删除配置不会关闭已经建立的 SSH 终端。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.DESTRUCTIVE, exclusive = true)
    public Map<String, Object> deleteSshConnection(
            @P("待删除的 profileId") String profileId, @P("最新配置版本号") Long version) {
        Scope scope = scope();
        return invoke(() -> connections.delete(scope.userId(), scope.sessionId(), profileId, version));
    }

    @Tool("""
            经当前 Agent 测试已保存的 SSH 连接，只做握手和认证，不启动 Shell、不执行命令，结束后释放连接。
            首次调用只传 profileId、version，使用已保存的可信指纹。
            返回 confirmationRequired 时，向用户展示主机、端口、新旧 SHA256 指纹并请求核验，禁止自行接受指纹。
            调用 request_user_input(type=CONFIRMATION, toolName="testSshConnection")，
            argumentsJson 使用返回的 confirmationArguments（profileId、version、fingerprint；不包含 confirmationRequestId），
            选项 intent 使用 CONFIRM 和 REJECT。用户确认后再传该 fingerprint 及问题卡片的 confirmationRequestId。
            确认凭证绑定当前线程、配置版本和指纹，且仅可消费一次。测试成功会保存可信指纹及最近连接时间。
            """)
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE, exclusive = true)
    public Map<String, Object> testSshConnection(
            @P("已保存的 profileId") String profileId,
            @P("最新配置版本号") Long version,
            @P(value = "用户已核验的 SHA256 指纹；通常省略", required = false) String fingerprint,
            @P(value = "确认该指纹的问题卡片 ID；仅 fingerprint 非空时必填", required = false) String confirmationRequestId) {
        Scope scope = scope();
        if (fingerprint != null) requireConfirmation(profileId, version, fingerprint, confirmationRequestId);
        Map<String, Object> result = invoke(() -> connections.test(
                scope.userId(), scope.sessionId(), profileId, version, fingerprint));
        if (!Boolean.TRUE.equals(result.get("confirmationRequired"))) return result;
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("confirmationArguments", Map.of("profileId", result.get("profileId"),
                "version", result.get("version"), "fingerprint", result.get("fingerprint")));
        return response;
    }

    private void requireConfirmation(String profileId, Long version, String fingerprint, String requestId) {
        if (profileId == null || version == null) {
            throw AiToolException.modelCorrectable("SSH_VALIDATION", "请提供 SSH 配置标识和版本号。",
                    "先调用 listSshConnections 获取 profileId 和 version。");
        }
        String threadId = AiToolContext.getThreadId();
        AiUserInputRequest request = requests.find(requestId);
        String argumentsHash = AiUserInputService.confirmationArgumentsHash(JsonUtil.toJsonString(
                Map.of("profileId", profileId, "version", version, "fingerprint", fingerprint)));
        long now = System.currentTimeMillis();
        if (threadId == null || request == null || !threadId.equals(request.getThreadId())
                || !AiUserInputRequest.TYPE_CONFIRMATION.equals(request.getRequestType())
                || !AiUserInputRequest.STATUS_ANSWERED.equals(request.getStatus())
                || !"testSshConnection".equals(request.getToolName())
                || !argumentsHash.equals(request.getArgumentsHash())
                || request.getConfirmationConsumedAt() != null
                || request.getExpiresAt() != null && request.getExpiresAt() <= now
                || request.options().stream().noneMatch(option -> Objects.equals(option.getValue(), request.getAnswer())
                    && ("CONFIRM".equals(option.getIntent()) || "APPROVE".equals(option.getIntent())))
                || !requests.consumeConfirmation(requestId, threadId, "testSshConnection", argumentsHash, now)) {
            throw AiToolException.userActionRequired("SSH_HOST_KEY_CONFIRMATION_REQUIRED",
                    "SSH 主机指纹需要用户核验确认。",
                    "通过 request_user_input 确认该配置版本和指纹后，传入对应 confirmationRequestId。");
        }
    }

    private Scope scope() {
        return new Scope(AiToolContext.requireSessionId(), AiToolContext.requireExecutionPolicy().getUserId());
    }

    private <T> T invoke(Supplier<T> action) {
        try { return action.get(); }
        catch (SshConnectionException error) {
            String code = "SSH_" + error.getKind().name();
            throw switch (error.getKind()) {
                case FORBIDDEN, SESSION_EXPIRED -> AiToolException.userActionRequired(code,
                        error.getMessage(), "请重新选择可访问的 Agent 会话。");
                case PERSISTENCE, UNAVAILABLE -> AiToolException.systemRetryable(code, error.getMessage(), error);
                default -> AiToolException.modelCorrectable(code, error.getMessage(),
                        "先调用 listSshConnections 或 getSshConnection，核对配置字段与最新版本。");
            };
        }
    }

    private record Scope(String sessionId, String userId) { }
}
