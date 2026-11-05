package org.leo.web.controller.agentnode.command;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.agentnode.SessionIdRequest;
import org.leo.web.dto.agentnode.command.CommandExecRequest;
import org.leo.web.dto.agentnode.command.SshConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileRequest;
import org.leo.web.dto.agentnode.command.SshProfileSaveRequest;
import org.leo.web.dto.agentnode.command.SshProfileConnectRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.service.ssh.AgentSshService;
import org.leo.web.service.ssh.SshConnectionProfiles;
import org.leo.web.util.ControllerUtil;
import org.leo.web.util.AuditLogUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Ordinary authenticated API endpoints; no separate WebSocket authentication surface. */
@RestController
@RequestMapping("/agent-node/terminal/ssh")
public class SshTerminalController {
    private final AgentSshService ssh;
    private final SshConnectionProfiles profiles;
    public SshTerminalController(AgentSshService ssh, SshConnectionProfiles profiles) {
        this.ssh = ssh; this.profiles = profiles;
    }
    private record Context(String user, String browser, AgentNodeSession parent, ComponentInvokeCapable node) { }
    private Context context(String id, HttpServletRequest request) {
        AgentNodeSession parent = ControllerUtil.getAgentNodeSession(id);
        ControllerUtil.requireCapability(parent, LocalForwardCapable.class);
        ComponentInvokeCapable node = ControllerUtil.requireCapability(parent, ComponentInvokeCapable.class);
        var currentUser = ControllerUtil.getCurrentUser(request);
        if (currentUser == null) throw ApiException.unauthorized("登录会话已结束");
        String user = currentUser.getUserId();
        var session = request.getSession(false);
        if (session == null) throw ApiException.unauthorized("登录会话已结束");
        String browser = session.getId();
        String binding = AgentSshService.class.getName();
        synchronized (session) {
            if (session.getAttribute(binding) == null) {
                session.setAttribute(binding, new HttpSessionBindingListener() {
                    @Override public void valueUnbound(HttpSessionBindingEvent event) { ssh.closeBrowser(browser); }
                });
            }
        }
        parent.touchLastActiveTime();
        return new Context(user, browser, parent, node);
    }
    @PostMapping("/profiles")
    public Map<String, Object> profiles(@RequestBody SessionIdRequest body, HttpServletRequest http) {
        if (body == null) throw ApiException.badRequest("SSH 请求为空");
        Context c = context(SshConnectRequest.id(body.sessionId()), http);
        try { return ApiResponse.success(profiles.list(c.user, c.parent)); }
        catch (ApiException error) { throw error; }
        catch (Exception error) { throw ApiException.serverError("读取 SSH 连接配置失败"); }
    }
    @PostMapping("/profiles/get")
    public Map<String, Object> getProfile(@RequestBody SshProfileRequest body, HttpServletRequest http) {
        if (body == null) throw ApiException.badRequest("SSH 请求为空");
        SshProfileRequest request = body.normalized();
        Context c = context(request.sessionId(), http);
        try { return ApiResponse.success(profiles.get(c.user, c.parent, request.profileId())); }
        catch (ApiException error) { throw error; }
        catch (Exception error) { throw ApiException.serverError("读取 SSH 连接配置失败"); }
    }
    @PostMapping("/profiles/save")
    public Map<String, Object> saveProfile(@RequestBody SshProfileSaveRequest body, HttpServletRequest http) {
        if (body == null) throw ApiException.badRequest("SSH 请求为空");
        SshProfileSaveRequest request = body.normalized();
        Context c = context(request.sessionId(), http);
        try {
            Map<String, Object> saved = profiles.save(c.user, c.parent, request);
            audit(c, (String) saved.get("profileId"), "SSH_PROFILE_SAVE", Map.of("profileId", saved.get("profileId"), "host", saved.get("host")), null);
            return ApiResponse.success(saved);
        }
        catch (ApiException error) { throw error; }
        catch (Exception error) { throw ApiException.serverError("保存 SSH 连接配置失败，请检查数据库和 SSH 加密密钥"); }
    }
    @PostMapping("/profiles/delete")
    public Map<String, Object> deleteProfile(@RequestBody SshProfileRequest body, HttpServletRequest http) {
        if (body == null) throw ApiException.badRequest("SSH 请求为空");
        SshProfileRequest request = body.normalized();
        SshProfileRequest.requireVersion(request.version());
        Context c = context(request.sessionId(), http);
        try {
            profiles.delete(c.user, c.parent, request);
            audit(c, request.profileId(), "SSH_PROFILE_DELETE", Map.of("profileId", request.profileId()), null);
            return ApiResponse.success();
        }
        catch (ApiException error) { throw error; }
        catch (Exception error) { throw ApiException.serverError("删除 SSH 连接配置失败"); }
    }
    @PostMapping("/profiles/connect")
    public Map<String, Object> connectProfile(@RequestBody SshProfileConnectRequest body, HttpServletRequest http) {
        if (body == null) throw ApiException.badRequest("SSH 请求为空");
        SshProfileConnectRequest request = body.normalized();
        Context c = context(request.sessionId(), http);
        try {
            Map<String, Object> profile = profiles.getForConnection(c.user, c.parent, request);
            if (request.fingerprint() == null && (!(profile.get("fingerprint") instanceof String pin) || pin.isBlank())) {
                String observed = ssh.probeFingerprint(c.user, c.browser, c.parent, c.node,
                        (String) profile.get("host"), ((Number) profile.get("port")).intValue());
                throw hostKeyConfirmation(profile, observed);
            }
            SshConnectRequest connection = profiles.resolveConnection(c.user, c.parent, request);
            try { ssh.connect(c.user, c.browser, c.parent, c.node, connection); }
            catch (AgentSshService.HostKeyChangedException changed) { throw hostKeyConfirmation(profile, changed.fingerprint()); }
            boolean saved = true;
            try { profiles.recordFingerprint(c.user, c.parent, request.profileId(), request.version(), connection.fingerprint()); }
            catch (Exception ignored) { saved = false; }
            audit(c, request.processId(), "COMMAND_INIT", Map.of("profileId", request.profileId(), "host", connection.host(),
                    "port", connection.port(), "username", connection.username(), "authType", connection.authType()), null);
            return ApiResponse.success(Map.of("profileSaved", saved));
        } catch (ApiException error) { throw error; }
        catch (net.schmizz.sshj.userauth.UserAuthException error) {
            audit(c, request.processId(), "COMMAND_INIT", Map.of("profileId", request.profileId()), "SSH 认证失败");
            throw ApiException.badRequest("SSH 认证失败，请编辑已保存的账号、密码或私钥");
        }
        catch (Exception error) {
            audit(c, request.processId(), "COMMAND_INIT", Map.of("profileId", request.profileId()), "SSH 连接失败");
            throw ApiException.badRequest("SSH 连接失败，请检查已保存凭据、主机指纹和 Agent 通道");
        }
    }
    private ApiException hostKeyConfirmation(Map<String, Object> profile, String fingerprint) {
        return ApiException.conflict("请确认 SSH 主机指纹", Map.of("errorCode", "SSH_HOST_KEY_CONFIRMATION_REQUIRED",
                "fingerprint", fingerprint, "previousFingerprint", profile.getOrDefault("fingerprint", ""),
                "host", profile.get("host"), "port", profile.get("port")));
    }
    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody CommandExecRequest body, HttpServletRequest http) {
        CommandExecRequest request = CommandExecRequest.normalize(body);
        if ("write".equals(request.type()) && request.cmd().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
            throw ApiException.badRequest("SSH 单次输入超过 1 MiB");
        Context c = context(request.sessionId(), http);
        try {
            Map<String, Object> result = ssh.command(c.user, c.browser, c.parent, request);
            if ("stop".equals(request.type())) audit(c, request.processId(), "COMMAND_STOP", Map.of(), null);
            return ApiResponse.success(result);
        }
        catch (ApiException error) { throw error; }
        catch (Exception error) { throw ApiException.badRequest("SSH 终端已断开或操作失败，请新建连接"); }
    }
    private void audit(Context context, String process, String operation, Map<String, Object> details, String error) {
        if (context.parent.getAgentNode() == null) return;
        Map<String, Object> params = new java.util.HashMap<>(details);
        params.put("sessionId", context.parent.getSessionId()); params.put("processId", process); params.put("connectionType", "ssh");
        // Never record credentials, terminal input or output, including passwords typed inside the PTY.
        if (error == null) AuditLogUtil.logSuccess(context.parent.getAgentNode(), operation, "SSH 终端", process,
                params, ApiResponse.CODE_SUCCESS, "操作成功", AuditLogUtil.getClientIp());
        else AuditLogUtil.logFailure(context.parent.getAgentNode(), operation, "SSH 终端", process,
                params, error, AuditLogUtil.getClientIp());
    }
}
