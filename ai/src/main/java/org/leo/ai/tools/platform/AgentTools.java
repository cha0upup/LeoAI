package org.leo.ai.tools.platform;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.service.AgentConnectionService;
import org.leo.service.AgentService;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.leo.ai.tools.platform.PlatformToolArguments.*;

@Component("platformAgentTools")
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class AgentTools {
    private final AgentService agentService;
    private final AgentConnectionService agentConnectionService;
    private final UserService userService;
    private final TeamService teamService;
    private final DisguiseService disguiseService;
    private final PlatformToolAccessService accessService;

    public AgentTools(AgentService agentService, AgentConnectionService agentConnectionService,
                       UserService userService, TeamService teamService,
                       DisguiseService disguiseService,
                       PlatformToolAccessService accessService) {
        this.agentService = agentService;
        this.agentConnectionService = agentConnectionService;
        this.userService = userService;
        this.teamService = teamService;
        this.disguiseService = disguiseService;
        this.accessService = accessService;
    }

    @Tool("测试指定 Agent 的连通性，不创建会话。返回 success、hostId、components 和 latencyMs；失败时返回 message。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> testAgentConnection(@P("待测试 Agent ID") String agentId) {
        String id = requireNonBlank(agentId, "agentId不能为空");
        accessService.requireVisible(agentService.findAgentById(id));
        return agentConnectionService.testConnection(id);
    }

    @Tool("列出当前用户可见的 Agent。createUserId、parentAgentId、permission 都是可选过滤条件，可组合使用。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Agent> listAgents(
            @P(value = "可选创建人 userId", required = false) String createUserId,
            @P(value = "可选父 Agent ID", required = false) String parentAgentId,
            @P(value = "可选权限：private/team/public", required = false) String permission) {
        String owner = trimToNull(createUserId);
        String parent = trimToNull(parentAgentId);
        String visibility = trimToNull(permission);
        List<Agent> result = new ArrayList<>();
        for (Agent agent : accessService.filterVisible(agentService.getAllAgents())) {
            if (agent == null) continue;
            if (owner != null && !owner.equals(agent.getCreateByUserId())) continue;
            if (parent != null && !parent.equals(agent.getParentAgentId())) continue;
            if (visibility != null && !visibility.equals(agent.getPermission())) continue;
            result.add(agent);
        }
        return result;
    }

    @Tool("根据 agentId 获取 Agent 详情。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Agent getAgentById(@P("Agent ID") String agentId) {
        return accessService.requireVisible(agentService.findAgentById(
                requireNonBlank(agentId, "agentId不能为空")));
    }

    @Tool("创建平台 Agent。agentName、connLink 必填；创建人和团队范围由当前用户身份强制约束，未传 agentId 会自动生成。maxReqCount 是包含首次请求的最大请求总数，范围 1-10，1 表示不重试。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> addAgent(
                                         @P("Agent 名称") String agentName,
                                         @P(value = "创建人用户 ID；省略时使用当前用户", required = false) String createByUserId,
                                         @P("连接链接") String connLink,
                                         @P(value = "团队 ID", required = false) String teamId,
                                         @P(value = "父 Agent ID", required = false) String parentAgentId,
                                         @P(value = "传输协议；省略时使用实体默认值", required = false) String protocol,
                                         @P(value = "请求头配置", required = false) String headers,
                                         @P(value = "请求 Disguise ID", required = false) String reqDisguiseId,
                                         @P(value = "响应 Disguise ID", required = false) String respDisguiseId,
                                         @P(value = "是否启用代理：1/0", required = false) Integer proxyEnabled,
                                         @P(value = "代理类型", required = false) String proxyType,
                                         @P(value = "代理主机", required = false) String proxyHost,
                                         @P(value = "代理端口", required = false) Integer proxyPort,
                                         @P(value = "最大请求总数，1-10", required = false) Integer maxReqCount,
                                         @P(value = "可见范围：private/team/public；省略时使用默认范围", required = false) String permission,
                                         @P(value = "最后心跳时间", required = false) String lastHeartbeat,
                                         @P(value = "心跳间隔", required = false) Integer heartbeatInterval,
                                         @P(value = "备注", required = false) String remark,
                                         @P(value = "Agent ID；省略时自动生成", required = false) String agentId,
                                         @P(value = "URL 策略 JSON", required = false) String urlStrategy,
                                         @P(value = "Padding 策略 JSON", required = false) String paddingStrategy,
                                         @P(value = "Header Noise 策略 JSON", required = false) String headerNoiseStrategy,
                                         @P(value = "TLS 指纹策略 JSON", required = false) String tlsFingerprintStrategy,
                                         @P(value = "组件类名策略 JSON", required = false) String componentClassNameStrategy) {
        User caller = accessService.requireCurrentUser();
        boolean admin = AccessPolicy.isAdmin(caller);
        String requestedOwner = trimToNull(createByUserId);
        if (!admin && requestedOwner != null
                && !caller.getUserId().equals(requestedOwner)) {
            throw new SecurityException("不能以其他用户身份创建 Agent");
        }
        String effectivePermission = accessService.normalizePermission(permission);
        if (AccessPolicy.PERMISSION_PUBLIC.equals(effectivePermission) && !admin) {
            throw new SecurityException("只有管理员可以创建公开 Agent");
        }
        String effectiveTeamId = resolveTeamId(caller, admin, teamId, null);
        String effectiveParentId = trimToNull(parentAgentId);
        if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
            accessService.requireVisible(agentService.findAgentById(effectiveParentId));
        }

        Agent agent = new Agent();
        agent.setAgentId(defaultIfBlank(agentId, UUID.randomUUID().toString()));
        agent.setAgentName(requireNonBlank(agentName, "agentName不能为空"));
        agent.setCreateByUserId(admin && requestedOwner != null
                ? requestedOwner : caller.getUserId());
        agent.setConnLink(requireNonBlank(connLink, "connLink不能为空"));
        agent.setTeamId(effectiveTeamId);
        agent.setParentAgentId(effectiveParentId);
        agent.setProtocol(defaultIfBlank(protocol, agent.getProtocol()));
        agent.setHeaders(trimToNull(headers));
        agent.setReqDisguiseId(trimToNull(reqDisguiseId));
        agent.setRespDisguiseId(trimToNull(respDisguiseId));
        if (proxyEnabled != null) {
            agent.setProxyEnabled(proxyEnabled);
        }
        if (proxyType != null) {
            agent.setProxyType(trimToNull(proxyType));
        }
        if (proxyHost != null) {
            agent.setProxyHost(trimToNull(proxyHost));
        }
        if (proxyPort != null) {
            agent.setProxyPort(proxyPort);
        }
        if (maxReqCount != null) {
            agent.setMaxReqCount(maxReqCount);
        }
        agent.setPermission(effectivePermission);
        if (lastHeartbeat != null) {
            agent.setLastHeartbeat(trimToNull(lastHeartbeat));
        }
        if (heartbeatInterval != null) {
            agent.setHeartbeatInterval(heartbeatInterval);
        }
        agent.setRemark(trimToNull(remark));
        agent.setUrlStrategy(trimToNull(urlStrategy));
        agent.setPaddingStrategy(trimToNull(paddingStrategy));
        agent.setHeaderNoiseStrategy(trimToNull(headerNoiseStrategy));
        agent.setTlsFingerprintStrategy(trimToNull(tlsFingerprintStrategy));
        agent.setComponentClassNameStrategy(trimToNull(componentClassNameStrategy));

        validateAgentRelations(agent);
        boolean created = agentService.insertAgent(agent);
        return buildResult("created", created, agent.getAgentId(), agent.getAgentName());
    }

    @Tool("更新平台 Agent。agentId 必填，其余字段按需更新。maxReqCount 是包含首次请求的最大请求总数，范围 1-10，1 表示不重试。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> updateAgent(
                                            @P("待更新 Agent ID") String agentId,
                                            @P(value = "新名称", required = false) String agentName,
                                            @P(value = "新创建人用户 ID", required = false) String createByUserId,
                                            @P(value = "新连接链接", required = false) String connLink,
                                            @P(value = "新团队 ID；空字符串表示清空", required = false) String teamId,
                                            @P(value = "新父 Agent ID；空字符串表示清空", required = false) String parentAgentId,
                                            @P(value = "新传输协议", required = false) String protocol,
                                            @P(value = "新请求头配置；空字符串表示清空", required = false) String headers,
                                            @P(value = "新请求 Disguise ID；空字符串表示清空", required = false) String reqDisguiseId,
                                            @P(value = "新响应 Disguise ID；空字符串表示清空", required = false) String respDisguiseId,
                                            @P(value = "是否启用代理：1/0", required = false) Integer proxyEnabled,
                                            @P(value = "新代理类型", required = false) String proxyType,
                                            @P(value = "新代理主机", required = false) String proxyHost,
                                            @P(value = "新代理端口", required = false) Integer proxyPort,
                                            @P(value = "最大请求总数，1-10", required = false) Integer maxReqCount,
                                            @P(value = "新可见范围：private/team/public", required = false) String permission,
                                            @P(value = "最后心跳时间", required = false) String lastHeartbeat,
                                            @P(value = "心跳间隔", required = false) Integer heartbeatInterval,
                                            @P(value = "新备注", required = false) String remark,
                                            @P(value = "新 URL 策略 JSON", required = false) String urlStrategy,
                                            @P(value = "新 Padding 策略 JSON", required = false) String paddingStrategy,
                                            @P(value = "新 Header Noise 策略 JSON", required = false) String headerNoiseStrategy,
                                            @P(value = "新 TLS 指纹策略 JSON", required = false) String tlsFingerprintStrategy,
                                            @P(value = "新组件类名策略 JSON", required = false) String componentClassNameStrategy) {
        Agent existing = agentService.findAgentById(requireNonBlank(agentId, "agentId不能为空"));
        accessService.requireModifiable(existing);
        User caller = accessService.requireCurrentUser();
        boolean admin = AccessPolicy.isAdmin(caller);

        if (!isBlank(agentName)) {
            existing.setAgentName(agentName.trim());
        }
        if (!isBlank(createByUserId)) {
            if (!admin && !Objects.equals(
                    existing.getCreateByUserId(), createByUserId.trim())) {
                throw new SecurityException("不能变更 Agent 创建人");
            }
            if (admin) existing.setCreateByUserId(createByUserId.trim());
        }
        if (!isBlank(connLink)) {
            existing.setConnLink(connLink.trim());
        }
        if (teamId != null) {
            existing.setTeamId(resolveTeamId(caller, admin, teamId, existing));
        }
        if (parentAgentId != null) {
            String effectiveParentId = trimToNull(parentAgentId);
            if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
                accessService.requireVisible(agentService.findAgentById(effectiveParentId));
            }
            existing.setParentAgentId(effectiveParentId);
        }
        if (!isBlank(protocol)) {
            existing.setProtocol(protocol.trim());
        }
        if (headers != null) {
            existing.setHeaders(trimToNull(headers));
        }
        if (reqDisguiseId != null) {
            existing.setReqDisguiseId(trimToNull(reqDisguiseId));
        }
        if (respDisguiseId != null) {
            existing.setRespDisguiseId(trimToNull(respDisguiseId));
        }
        if (proxyEnabled != null) {
            existing.setProxyEnabled(proxyEnabled);
        }
        if (proxyType != null) {
            existing.setProxyType(trimToNull(proxyType));
        }
        if (proxyHost != null) {
            existing.setProxyHost(trimToNull(proxyHost));
        }
        if (proxyPort != null) {
            existing.setProxyPort(proxyPort);
        }
        if (maxReqCount != null) {
            existing.setMaxReqCount(maxReqCount);
        }
        if (permission != null) {
            String effectivePermission = accessService.normalizePermission(permission);
            if (AccessPolicy.PERMISSION_PUBLIC.equals(effectivePermission) && !admin) {
                throw new SecurityException("只有管理员可以设置公开 Agent");
            }
            existing.setPermission(effectivePermission);
        }
        if (lastHeartbeat != null) {
            existing.setLastHeartbeat(trimToNull(lastHeartbeat));
        }
        if (heartbeatInterval != null) {
            existing.setHeartbeatInterval(heartbeatInterval);
        }
        if (remark != null) {
            existing.setRemark(trimToNull(remark));
        }
        if (urlStrategy != null) {
            existing.setUrlStrategy(trimToNull(urlStrategy));
        }
        if (paddingStrategy != null) {
            existing.setPaddingStrategy(trimToNull(paddingStrategy));
        }
        if (headerNoiseStrategy != null) {
            existing.setHeaderNoiseStrategy(trimToNull(headerNoiseStrategy));
        }
        if (tlsFingerprintStrategy != null) {
            existing.setTlsFingerprintStrategy(trimToNull(tlsFingerprintStrategy));
        }
        if (componentClassNameStrategy != null) {
            existing.setComponentClassNameStrategy(trimToNull(componentClassNameStrategy));
        }

        validateAgentRelations(existing);
        boolean updated = agentService.updateAgentById(existing);
        return buildResult("updated", updated, existing.getAgentId(), existing.getAgentName());
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定 Agent，并级联删除其全部子孙节点；同时关闭这些节点的在线会话并清理工作目录。")
    public Map<String, Object> deleteAgent(@P("待删除 Agent ID") String agentId) {
        Agent agent = agentService.findAgentById(requireNonBlank(agentId, "agentId不能为空"));
        accessService.requireModifiable(agent);
        boolean deleted = agentService.deleteAgentById(agent.getAgentId());
        return buildResult("deleted", deleted, agent.getAgentId(), agent.getAgentName());
    }

    private void validateAgentRelations(Agent agent) {
        if (userService.getUserById(agent.getCreateByUserId()) == null) {
            throw new IllegalArgumentException("创建用户不存在");
        }
        if (!isBlank(agent.getTeamId()) && teamService.getTeamById(agent.getTeamId()) == null) {
            throw new IllegalArgumentException("团队不存在");
        }
        if (!isBlank(agent.getParentAgentId())
                && agentService.findAgentById(agent.getParentAgentId()) == null) {
            throw new IllegalArgumentException("父 Agent 不存在");
        }
        if (!isBlank(agent.getReqDisguiseId())) {
            disguiseService.getDisguiseById(agent.getReqDisguiseId());
        }
        if (!isBlank(agent.getRespDisguiseId())) {
            disguiseService.getDisguiseById(agent.getRespDisguiseId());
        }
    }

    private String resolveTeamId(User caller, boolean admin,
                                 String requestedTeamId, Agent existing) {
        String requested = trimToNull(requestedTeamId);
        if (admin) {
            return requested != null
                    ? requested
                    : existing != null ? existing.getTeamId() : caller.getTeamId();
        }
        String callerTeamId = trimToNull(caller.getTeamId());
        if (requested != null && !requested.equals(callerTeamId)) {
            throw new SecurityException("不能把 Agent 分配到其他团队");
        }
        return callerTeamId != null
                ? callerTeamId
                : existing != null ? existing.getTeamId() : null;
    }

    private Map<String, Object> buildResult(String status, boolean success, String agentId, String agentName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("success", success);
        result.put("agentId", agentId);
        result.put("agentName", agentName);
        return result;
    }
}
