package org.leo.ai.tools.platform;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.service.PuppetConnService;
import org.leo.service.PuppetService;
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

@Component("platformPuppetTools")
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class PuppetTools {
    private final PuppetService puppetService;
    private final PuppetConnService puppetConnService;
    private final UserService userService;
    private final TeamService teamService;
    private final DisguiseService disguiseService;
    private final PlatformToolAccessService accessService;

    public PuppetTools(PuppetService puppetService, PuppetConnService puppetConnService,
                       UserService userService, TeamService teamService,
                       DisguiseService disguiseService,
                       PlatformToolAccessService accessService) {
        this.puppetService = puppetService;
        this.puppetConnService = puppetConnService;
        this.userService = userService;
        this.teamService = teamService;
        this.disguiseService = disguiseService;
        this.accessService = accessService;
    }

    @Tool("测试指定 Puppet 的连通性，不创建会话。返回 success、hostId、components 和 latencyMs；失败时返回 message。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> testPuppetConnection(@P("待测试 Puppet ID") String puppetId) {
        String id = requireNonBlank(puppetId, "puppetId不能为空");
        accessService.requireVisible(puppetService.findPuppetById(id));
        return puppetConnService.testConnection(id);
    }

    @Tool("列出当前用户可见的 Puppet。createUserId、parentPuppetId、permission 都是可选过滤条件，可组合使用。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Puppet> listPuppets(
            @P(value = "可选创建人 userId", required = false) String createUserId,
            @P(value = "可选父 Puppet ID", required = false) String parentPuppetId,
            @P(value = "可选权限：private/team/public", required = false) String permission) {
        String owner = trimToNull(createUserId);
        String parent = trimToNull(parentPuppetId);
        String visibility = trimToNull(permission);
        List<Puppet> result = new ArrayList<>();
        for (Puppet puppet : accessService.filterVisible(puppetService.getAllPuppet())) {
            if (puppet == null) continue;
            if (owner != null && !owner.equals(puppet.getCreateByUserId())) continue;
            if (parent != null && !parent.equals(puppet.getParentPuppetId())) continue;
            if (visibility != null && !visibility.equals(puppet.getPermission())) continue;
            result.add(puppet);
        }
        return result;
    }

    @Tool("根据 puppetId 获取 Puppet 详情。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Puppet getPuppetById(@P("Puppet ID") String puppetId) {
        return accessService.requireVisible(puppetService.findPuppetById(
                requireNonBlank(puppetId, "puppetId不能为空")));
    }

    @Tool("创建平台 Puppet。puppetName、connLink 必填；创建人和团队范围由当前用户身份强制约束，未传 puppetId 会自动生成。maxReqCount 是包含首次请求的最大请求总数，范围 1-10，1 表示不重试。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> addPuppet(
                                         @P("Puppet 名称") String puppetName,
                                         @P(value = "创建人用户 ID；省略时使用当前用户", required = false) String createByUserId,
                                         @P("连接链接") String connLink,
                                         @P(value = "团队 ID", required = false) String teamId,
                                         @P(value = "父 Puppet ID", required = false) String parentPuppetId,
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
                                         @P(value = "Puppet ID；省略时自动生成", required = false) String puppetId,
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
            throw new SecurityException("不能以其他用户身份创建 Puppet");
        }
        String effectivePermission = accessService.normalizePermission(permission);
        if (AccessPolicy.PERMISSION_PUBLIC.equals(effectivePermission) && !admin) {
            throw new SecurityException("只有管理员可以创建公开 Puppet");
        }
        String effectiveTeamId = resolveTeamId(caller, admin, teamId, null);
        String effectiveParentId = trimToNull(parentPuppetId);
        if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
            accessService.requireVisible(puppetService.findPuppetById(effectiveParentId));
        }

        Puppet puppet = new Puppet();
        puppet.setPuppetId(defaultIfBlank(puppetId, UUID.randomUUID().toString()));
        puppet.setPuppetName(requireNonBlank(puppetName, "puppetName不能为空"));
        puppet.setCreateByUserId(admin && requestedOwner != null
                ? requestedOwner : caller.getUserId());
        puppet.setConnLink(requireNonBlank(connLink, "connLink不能为空"));
        puppet.setTeamId(effectiveTeamId);
        puppet.setParentPuppetId(effectiveParentId);
        puppet.setProtocol(defaultIfBlank(protocol, puppet.getProtocol()));
        puppet.setHeaders(trimToNull(headers));
        puppet.setReqDisguiseId(trimToNull(reqDisguiseId));
        puppet.setRespDisguiseId(trimToNull(respDisguiseId));
        if (proxyEnabled != null) {
            puppet.setProxyEnabled(proxyEnabled);
        }
        if (proxyType != null) {
            puppet.setProxyType(trimToNull(proxyType));
        }
        if (proxyHost != null) {
            puppet.setProxyHost(trimToNull(proxyHost));
        }
        if (proxyPort != null) {
            puppet.setProxyPort(proxyPort);
        }
        if (maxReqCount != null) {
            puppet.setMaxReqCount(maxReqCount);
        }
        puppet.setPermission(effectivePermission);
        if (lastHeartbeat != null) {
            puppet.setLastHeartbeat(trimToNull(lastHeartbeat));
        }
        if (heartbeatInterval != null) {
            puppet.setHeartbeatInterval(heartbeatInterval);
        }
        puppet.setRemark(trimToNull(remark));
        puppet.setUrlStrategy(trimToNull(urlStrategy));
        puppet.setPaddingStrategy(trimToNull(paddingStrategy));
        puppet.setHeaderNoiseStrategy(trimToNull(headerNoiseStrategy));
        puppet.setTlsFingerprintStrategy(trimToNull(tlsFingerprintStrategy));
        puppet.setComponentClassNameStrategy(trimToNull(componentClassNameStrategy));

        validatePuppetRelations(puppet);
        boolean created = puppetService.insertPuppet(puppet);
        return buildResult("created", created, puppet.getPuppetId(), puppet.getPuppetName());
    }

    @Tool("更新平台 Puppet。puppetId 必填，其余字段按需更新。maxReqCount 是包含首次请求的最大请求总数，范围 1-10，1 表示不重试。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> updatePuppet(
                                            @P("待更新 Puppet ID") String puppetId,
                                            @P(value = "新名称", required = false) String puppetName,
                                            @P(value = "新创建人用户 ID", required = false) String createByUserId,
                                            @P(value = "新连接链接", required = false) String connLink,
                                            @P(value = "新团队 ID；空字符串表示清空", required = false) String teamId,
                                            @P(value = "新父 Puppet ID；空字符串表示清空", required = false) String parentPuppetId,
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
        Puppet existing = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        accessService.requireModifiable(existing);
        User caller = accessService.requireCurrentUser();
        boolean admin = AccessPolicy.isAdmin(caller);

        if (!isBlank(puppetName)) {
            existing.setPuppetName(puppetName.trim());
        }
        if (!isBlank(createByUserId)) {
            if (!admin && !Objects.equals(
                    existing.getCreateByUserId(), createByUserId.trim())) {
                throw new SecurityException("不能变更 Puppet 创建人");
            }
            if (admin) existing.setCreateByUserId(createByUserId.trim());
        }
        if (!isBlank(connLink)) {
            existing.setConnLink(connLink.trim());
        }
        if (teamId != null) {
            existing.setTeamId(resolveTeamId(caller, admin, teamId, existing));
        }
        if (parentPuppetId != null) {
            String effectiveParentId = trimToNull(parentPuppetId);
            if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
                accessService.requireVisible(puppetService.findPuppetById(effectiveParentId));
            }
            existing.setParentPuppetId(effectiveParentId);
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
                throw new SecurityException("只有管理员可以设置公开 Puppet");
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

        validatePuppetRelations(existing);
        boolean updated = puppetService.updatePuppetById(existing);
        return buildResult("updated", updated, existing.getPuppetId(), existing.getPuppetName());
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定 Puppet，并级联删除其全部子孙节点；同时关闭这些节点的在线会话并清理工作目录。")
    public Map<String, Object> deletePuppet(@P("待删除 Puppet ID") String puppetId) {
        Puppet puppet = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        accessService.requireModifiable(puppet);
        boolean deleted = puppetService.deletePuppetById(puppet.getPuppetId());
        return buildResult("deleted", deleted, puppet.getPuppetId(), puppet.getPuppetName());
    }

    private void validatePuppetRelations(Puppet puppet) {
        if (userService.getUserById(puppet.getCreateByUserId()) == null) {
            throw new IllegalArgumentException("创建用户不存在");
        }
        if (!isBlank(puppet.getTeamId()) && teamService.getTeamById(puppet.getTeamId()) == null) {
            throw new IllegalArgumentException("团队不存在");
        }
        if (!isBlank(puppet.getParentPuppetId())
                && puppetService.findPuppetById(puppet.getParentPuppetId()) == null) {
            throw new IllegalArgumentException("父 Puppet 不存在");
        }
        if (!isBlank(puppet.getReqDisguiseId())) {
            disguiseService.getDisguiseById(puppet.getReqDisguiseId());
        }
        if (!isBlank(puppet.getRespDisguiseId())) {
            disguiseService.getDisguiseById(puppet.getRespDisguiseId());
        }
    }

    private String resolveTeamId(User caller, boolean admin,
                                 String requestedTeamId, Puppet existing) {
        String requested = trimToNull(requestedTeamId);
        if (admin) {
            return requested != null
                    ? requested
                    : existing != null ? existing.getTeamId() : caller.getTeamId();
        }
        String callerTeamId = trimToNull(caller.getTeamId());
        if (requested != null && !requested.equals(callerTeamId)) {
            throw new SecurityException("不能把 Puppet 分配到其他团队");
        }
        return callerTeamId != null
                ? callerTeamId
                : existing != null ? existing.getTeamId() : null;
    }

    private Map<String, Object> buildResult(String status, boolean success, String puppetId, String puppetName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("success", success);
        result.put("puppetId", puppetId);
        result.put("puppetName", puppetName);
        return result;
    }
}
