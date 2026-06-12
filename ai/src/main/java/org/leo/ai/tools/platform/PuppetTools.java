package org.leo.ai.tools.platform;

import org.leo.core.entity.Puppet;
import org.leo.service.PuppetConnService;
import org.leo.service.PuppetService;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component("platformPuppetTools")
public class PuppetTools {
    private final PuppetService puppetService;
    private final PuppetConnService puppetConnService;
    private final UserService userService;
    private final TeamService teamService;
    private final DisguiseService disguiseService;

    public PuppetTools(PuppetService puppetService, PuppetConnService puppetConnService,
                       UserService userService, TeamService teamService, DisguiseService disguiseService) {
        this.puppetService = puppetService;
        this.puppetConnService = puppetConnService;
        this.userService = userService;
        this.teamService = teamService;
        this.disguiseService = disguiseService;
    }

    @Tool("测试指定 Puppet 的连通性，不创建会话。返回 success、hostId、components 和 latencyMs；失败时返回 message。")
    public Map<String, Object> testPuppetConnection(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) {
            throw new IllegalArgumentException("puppetId 不能为空");
        }
        return puppetConnService.testConnection(puppetId.trim());
    }

    @Tool("获取当前平台所有 Puppet。")
    public List<Puppet> getAllPuppet() {
        return puppetService.getAllPuppet();
    }

    @Tool("根据 puppetId 获取 Puppet 详情。")
    public Puppet getPuppetById(String puppetId) {
        Puppet puppet = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        if (puppet == null) {
            throw new IllegalArgumentException("Puppet不存在");
        }
        return puppet;
    }

    @Tool("根据创建人 userId 获取 Puppet 列表。")
    public List<Puppet> getPuppetsByCreateUserId(String createUserId) {
        return puppetService.findPuppetByCreateUserId(requireNonBlank(createUserId, "createUserId不能为空"));
    }

    @Tool("根据 parentPuppetId 获取子 Puppet 列表。")
    public List<Puppet> getPuppetsByParentPuppetId(String parentPuppetId) {
        return puppetService.findPuppetByParentPuppetId(requireNonBlank(parentPuppetId, "parentPuppetId不能为空"));
    }

    @Tool("根据权限获取 Puppet 列表，例如 read 或 write。")
    public List<Puppet> getPuppetsByPermission(String permission) {
        return puppetService.findPuppetByPermission(requireNonBlank(permission, "permission不能为空"));
    }

    @Tool("创建平台 Puppet。puppetName、createByUserId、connLink 必填；未传 puppetId 会自动生成。urlStrategy 为 JSON 格式的 URL 随机化策略配置。paddingStrategy 为 JSON 格式的请求体 Padding 策略配置。headerNoiseStrategy 为 JSON 格式的 Header 噪声注入策略配置。tlsFingerprintStrategy 为 JSON 格式的 TLS 指纹伪装策略配置。")
    public Map<String, Object> addPuppet(String puppetName, String createByUserId, String connLink,
                                         String teamId, String parentPuppetId, String protocol,
                                         String headers, String reqDisguiseId, String respDisguiseId,
                                         Integer proxyEnabled, String proxyType, String proxyHost, Integer proxyPort,
                                         Integer balanceEnabled, Integer maxReqCount, String permission,
                                         String lastHeartbeat, Integer heartbeatInterval,
                                         String remark, String puppetId, String urlStrategy,
                                         String paddingStrategy, String headerNoiseStrategy,
                                         String tlsFingerprintStrategy) {
        Puppet puppet = new Puppet();
        puppet.setPuppetId(defaultIfBlank(puppetId, UUID.randomUUID().toString()));
        puppet.setPuppetName(requireNonBlank(puppetName, "puppetName不能为空"));
        puppet.setCreateByUserId(requireNonBlank(createByUserId, "createByUserId不能为空"));
        puppet.setConnLink(requireNonBlank(connLink, "connLink不能为空"));
        puppet.setTeamId(trimToNull(teamId));
        puppet.setParentPuppetId(trimToNull(parentPuppetId));
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
        if (balanceEnabled != null) {
            puppet.setBalanceEnabled(balanceEnabled);
        }
        if (maxReqCount != null) {
            puppet.setMaxReqCount(maxReqCount);
        }
        if (permission != null) {
            puppet.setPermission(trimToNull(permission));
        }
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

        validatePuppetRelations(puppet);
        boolean created = puppetService.insertPuppet(puppet);
        return buildResult("created", created, puppet.getPuppetId(), puppet.getPuppetName());
    }

    @Tool("更新平台 Puppet。puppetId 必填，其余字段按需更新。urlStrategy 为 JSON 格式的 URL 随机化策略配置。paddingStrategy 为 JSON 格式的请求体 Padding 策略配置。headerNoiseStrategy 为 JSON 格式的 Header 噪声注入策略配置。tlsFingerprintStrategy 为 JSON 格式的 TLS 指纹伪装策略配置。")
    public Map<String, Object> updatePuppet(String puppetId, String puppetName, String createByUserId,
                                            String connLink, String teamId, String parentPuppetId,
                                            String protocol, String headers, String reqDisguiseId,
                                            String respDisguiseId, Integer proxyEnabled, String proxyType,
                                            String proxyHost, Integer proxyPort, Integer balanceEnabled,
                                            Integer maxReqCount, String permission, String lastHeartbeat,
                                            Integer heartbeatInterval, String remark, String urlStrategy,
                                            String paddingStrategy, String headerNoiseStrategy,
                                            String tlsFingerprintStrategy) {
        Puppet existing = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        if (existing == null) {
            throw new IllegalArgumentException("Puppet不存在");
        }

        if (!isBlank(puppetName)) {
            existing.setPuppetName(puppetName.trim());
        }
        if (!isBlank(createByUserId)) {
            existing.setCreateByUserId(createByUserId.trim());
        }
        if (!isBlank(connLink)) {
            existing.setConnLink(connLink.trim());
        }
        if (teamId != null) {
            existing.setTeamId(trimToNull(teamId));
        }
        if (parentPuppetId != null) {
            existing.setParentPuppetId(trimToNull(parentPuppetId));
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
        if (balanceEnabled != null) {
            existing.setBalanceEnabled(balanceEnabled);
        }
        if (maxReqCount != null) {
            existing.setMaxReqCount(maxReqCount);
        }
        if (permission != null) {
            existing.setPermission(trimToNull(permission));
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

        validatePuppetRelations(existing);
        boolean updated = puppetService.updatePuppetById(existing);
        return buildResult("updated", updated, existing.getPuppetId(), existing.getPuppetName());
    }

    @Tool("删除指定 Puppet。")
    public Map<String, Object> deletePuppet(String puppetId) {
        Puppet puppet = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        if (puppet == null) {
            throw new IllegalArgumentException("Puppet不存在");
        }
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

    private Map<String, Object> buildResult(String status, boolean success, String puppetId, String puppetName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("success", success);
        result.put("puppetId", puppetId);
        result.put("puppetName", puppetName);
        return result;
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
