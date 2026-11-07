package org.leo.service.ssh;

import org.leo.core.agent.capability.AgentNodeCapabilityRegistry;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.audit.AgentAuditService;
import org.leo.service.security.AgentAccessService;
import org.leo.service.ssh.SshConnectionException.Kind;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** SSH application use cases shared by callers; HTTP DTOs and transport implementations stay outside. */
@Service
public final class SshConnectionManagementService implements SshConnectionOperations {
    private static final Set<String> FIELDS = Set.of(
            "name", "host", "port", "username", "authType", "password", "privateKey", "passphrase");
    private final SshConnectionProfiles profiles;
    private final SshTransport ssh;
    private final UserService users;
    private final AgentAccessService access;
    private final AgentAuditService audit;

    public SshConnectionManagementService(SshConnectionProfiles profiles, SshTransport ssh,
                                           UserService users, AgentAccessService access,
                                           AgentAuditService audit) {
        this.profiles = profiles;
        this.ssh = ssh;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    @Override
    public List<Map<String, Object>> list(String userId, String sessionId) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, null, null, null, () -> profiles.list(userId, scope.parent()));
    }

    @Override
    public Map<String, Object> get(String userId, String sessionId, String profileId) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, null, null, null, () -> profiles.get(userId, scope.parent(), profileId));
    }

    @Override
    public Map<String, Object> create(String userId, String sessionId, Map<String, Object> config) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, "SSH_PROFILE_SAVE", "AI新增SSH配置", "create", () ->
                profiles.save(userId, scope.parent(), saveCommand(null, null, config, Map.of())));
    }

    @Override
    public Map<String, Object> update(String userId, String sessionId, String profileId,
                                      Long version, Map<String, Object> patch) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, "SSH_PROFILE_SAVE", "AI修改SSH配置", profileId, () -> {
            Map<String, Object> previous = profiles.get(userId, scope.parent(), profileId);
            return profiles.save(userId, scope.parent(), saveCommand(profileId, version, patch, previous));
        });
    }

    @Override
    public Map<String, Object> delete(String userId, String sessionId, String profileId, Long version) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, "SSH_PROFILE_DELETE", "AI删除SSH配置", profileId, () -> {
            String id = SshInputValidation.normalizeProfileId(profileId);
            profiles.delete(userId, scope.parent(), id, version);
            return Map.of("profileId", id, "deleted", true);
        });
    }

    @Override
    public Map<String, Object> test(String userId, String sessionId, String profileId,
                                    Long version, String confirmedFingerprint) {
        Scope scope = scope(userId, sessionId);
        return execute(scope, "SSH_CONNECTION_TEST", "AI测试SSH连接", profileId, () -> {
            requireTransport(scope);
            var request = new SshProfileConnectCommand("test-" + UUID.randomUUID(), profileId, version, confirmedFingerprint);
            Map<String, Object> profile = profiles.getForConnection(userId, scope.parent(), request);
            ComponentInvokeCapable node = (ComponentInvokeCapable) scope.parent().getAgentNode();
            boolean needsConfirmation = confirmedFingerprint == null
                    && (!(profile.get("fingerprint") instanceof String pin) || pin.isBlank());
            // Storage/decryption failures belong to the application boundary, not the transport result.
            SshConnectionSpec connection = needsConfirmation ? null : profiles.resolveConnection(userId, scope.parent(), request);
            long started = System.nanoTime();
            try {
                if (needsConfirmation) {
                    String observed = ssh.probeFingerprint(userId, "ai-ssh-test", scope.parent(), node,
                            (String) profile.get("host"), ((Number) profile.get("port")).intValue());
                    return confirmationRequired(profile, observed);
                }
                ssh.testConnection(userId, "ai-ssh-test", scope.parent(), node, connection);
                Map<String, Object> result = testResult(profile, true, started);
                result.put("fingerprint", connection.fingerprint());
                // A concurrent edit/delete must not be overwritten or reported as an authentication failure.
                boolean saved = true;
                try { profiles.recordFingerprint(userId, scope.parent(), profileId, version,
                        (String) profile.getOrDefault("fingerprint", ""), connection.fingerprint()); }
                catch (Exception ignored) { saved = false; }
                result.put("profileSaved", saved);
                return result;
            } catch (SshTransport.HostKeyChangedException changed) {
                return confirmationRequired(profile, changed.fingerprint());
            } catch (SshTransport.AuthenticationException error) {
                Map<String, Object> result = testResult(profile, false, started);
                result.put("errorCode", "SSH_AUTHENTICATION_FAILED");
                result.put("message", "SSH 认证失败，请检查已保存的账号、密码或私钥");
                return result;
            } catch (SshConnectionException error) {
                throw error;
            } catch (Exception error) {
                Map<String, Object> result = testResult(profile, false, started);
                result.put("errorCode", "SSH_CONNECTION_FAILED");
                result.put("message", "SSH 连接测试失败，请检查主机、端口、密钥格式和 Agent 通道");
                return result;
            }
        });
    }

    private Map<String, Object> confirmationRequired(Map<String, Object> profile, String fingerprint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileId", profile.get("profileId")); result.put("version", profile.get("version"));
        result.put("host", profile.get("host")); result.put("port", profile.get("port"));
        result.put("connected", false); result.put("confirmationRequired", true);
        result.put("errorCode", "SSH_HOST_KEY_CONFIRMATION_REQUIRED");
        result.put("fingerprint", fingerprint); result.put("previousFingerprint", profile.getOrDefault("fingerprint", ""));
        return result;
    }

    private Map<String, Object> testResult(Map<String, Object> profile, boolean connected, long started) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("profileId", "version", "host", "port", "username", "authType"))
            result.put(field, profile.get(field));
        result.put("connected", connected); result.put("viaAgent", true);
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    private SshProfileSaveCommand saveCommand(String profileId, Long version,
                                               Map<String, Object> fields, Map<String, Object> previous) {
        if (fields == null || fields.isEmpty() || !FIELDS.containsAll(fields.keySet()))
            throw new SshConnectionException(Kind.VALIDATION, "SSH 配置字段为空或包含未知字段");
        Map<String, Object> values = new LinkedHashMap<>(previous);
        values.putAll(fields);
        return new SshProfileSaveCommand(profileId, version,
                text(values, "name"), text(values, "host"), port(values.get("port")),
                text(values, "username"), text(values, "authType"),
                text(fields, "password"), text(fields, "privateKey"), text(fields, "passphrase"));
    }

    private String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new SshConnectionException(Kind.VALIDATION, "SSH 配置字段类型无效：" + name);
    }

    private Integer port(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Number number) return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) { }
        throw new SshConnectionException(Kind.VALIDATION, "SSH 端口必须是整数");
    }

    private Scope scope(String userId, String sessionId) {
        AgentNodeSession parent = sessionId == null || sessionId.isBlank() ? null : AgentNodeSessionContainer.getSession(sessionId);
        if (parent == null) throw new SshConnectionException(Kind.SESSION_EXPIRED, "当前 Agent 会话已结束");
        try {
            User user = userId == null || userId.isBlank() ? null : users.getUserById(userId);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !access.canAccessSession(parent, user))
                throw new SshConnectionException(Kind.FORBIDDEN, "当前用户无权访问该 Agent 会话");
            parent.touchLastActiveTime();
            return new Scope(user, parent);
        } catch (SshConnectionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SshConnectionException(Kind.PERSISTENCE, "读取 SSH 用户或会话权限失败，请检查存储服务");
        }
    }

    private void requireTransport(Scope scope) {
        var node = scope.parent().getAgentNode();
        if (!AgentNodeCapabilityRegistry.supports(node, LocalForwardCapable.class)
                || !AgentNodeCapabilityRegistry.supports(node, ComponentInvokeCapable.class))
            throw new SshConnectionException(Kind.UNSUPPORTED_CAPABILITY, "当前 Agent 不支持 SSH 中转连接");
    }

    private <T> T execute(Scope scope, String operation, String name, String id, Supplier<T> action) {
        try {
            T result = action.get();
            boolean failedTest = result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("connected"));
            String auditId = result instanceof Map<?, ?> map && map.get("profileId") instanceof String profileId ? profileId : id;
            record(scope, operation, name, auditId, !failedTest, failedTest ? "SSH 连接尚未验证成功" : null);
            return result;
        } catch (SshConnectionException error) {
            record(scope, operation, name, id, false, error.getMessage());
            throw error;
        } catch (RuntimeException error) {
            String message = "SSH 配置操作失败，请检查数据库和 SSH 加密密钥";
            record(scope, operation, name, id, false, message);
            throw new SshConnectionException(Kind.PERSISTENCE, message);
        }
    }

    private void record(Scope scope, String operation, String name, String id, boolean success, String error) {
        if (operation == null) return;
        // Deliberately exclude the input map, credentials, private keys and transport exception messages.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("profileId", id); params.put("agentId", scope.parent().resolveAgentId());
        audit.logOperationAs(scope.user(), scope.parent().getSessionId(), scope.parent().getAgentNode(),
                operation, name, id, params, success ? 200 : null, success ? "操作成功" : null,
                success ? "SUCCESS" : "FAILED", error);
    }

    private record Scope(User user, AgentNodeSession parent) { }
}
