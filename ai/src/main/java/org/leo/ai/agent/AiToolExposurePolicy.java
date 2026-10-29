package org.leo.ai.agent;

import org.leo.ai.service.SkillMeta;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.agent.capability.AgentNodeCapabilityRegistry;
import org.leo.core.session.AgentNodeSession;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 控制每次模型调用真正可见的工具集合。
 *
 * <p>基础工具常驻；Skill 声明的专项工具在激活后由动态 ToolProvider 追加；
 * Agent 工具还会按当前节点 capability 过滤，避免把必然不可用的 Schema 发给模型。
 */
@Component
public class AiToolExposurePolicy {

    private static final Set<String> CORE_TOOLS = Set.of(
            "activate_skill", "request_user_input", "get_tool_result_archive",
            "createPlan", "updatePlanStep", "completePlan",
            "workspaceList", "workspaceReadText", "workspaceSearch",
            "workspaceWriteText", "workspaceApplyPatch", "workspacePromote", "workspaceDelete",
            "workspaceExec", "workspaceExecStatus", "workspaceExecCancel",
            "webSearch", "webFetch", "webFetchToWorkspace",
            "getBasicInfo", "exec", "queryTask", "stopTask",
            "startDownloadTask", "startUploadTask", "stageRemoteFileToWorkspace",
            "queryRemoteFileStage", "readTextFile", "searchFileContent",
            "readResources", "getClassBytecode",
            // Agent 管理经常需要先读取 Disguise，保持只读发现能力常驻。
            "getDisguises", "getDisguiseById"
    );

    private static final Map<String, String> AGENT_CAPABILITIES = capabilityMap();

    private final AiRuntimeResolver runtimeResolver;
    private final SkillRegistryService skillRegistry;

    public AiToolExposurePolicy(AiRuntimeResolver runtimeResolver,
                                SkillRegistryService skillRegistry) {
        this.runtimeResolver = runtimeResolver;
        this.skillRegistry = skillRegistry;
    }

    /** 单次模型调用批量计算可见工具，避免为每个 Schema 重复读取 Skill 目录。 */
    public Set<String> visibleToolNames(AiToolAuthorizationPolicy.AiAgentScope scope,
                                        Object memoryId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return Set.of();
        String skillScope = scope == AiToolAuthorizationPolicy.AiAgentScope.PLATFORM
                ? SkillRegistryService.SCOPE_PLATFORM
                : SkillRegistryService.SCOPE_AGENT_NODE;
        List<SkillMeta> skills = skillRegistry.listSkills(skillScope);
        // 所有清单声明都参与门控；禁用、草稿或校验失败的 Skill 也按 fail-closed 隐藏其工具。
        List<SkillMeta> declaredSkills = skillRegistry.listAllSkills(skillScope);
        Set<String> gatedTools = new HashSet<>();
        for (SkillMeta skill : declaredSkills) gatedTools.addAll(skill.getRequiredTools());
        AiRuntimeState runtime = runtimeResolver.resolve(scope, memoryId);
        Set<String> activeTools = runtime == null || runtime.getActivatedSkills().isEmpty()
                ? Set.of() : activatedTools(skills, runtime.getActivatedSkills());
        AgentNodeSession agentSession = scope == AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE
                ? runtimeResolver.resolveAgentSession(memoryId) : null;
        Set<String> visible = new HashSet<>();
        for (String toolName : toolNames) {
            if (toolName == null || toolName.isBlank()) continue;
            if (scope == AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE
                    && !supportsCapability(agentSession, toolName)) continue;
            if (CORE_TOOLS.contains(toolName)
                    || !gatedTools.contains(toolName)
                    || activeTools.contains(toolName)) {
                visible.add(toolName);
            }
        }
        return Set.copyOf(visible);
    }

    private boolean supportsCapability(AgentNodeSession session, String toolName) {
        String capability = AGENT_CAPABILITIES.get(toolName);
        if (capability == null) return true;
        return session != null && AgentNodeCapabilityRegistry.supports(
                session.getAgentNode(), capability);
    }

    private static Set<String> activatedTools(List<SkillMeta> skills,
                                              Set<String> activatedSkills) {
        Map<String, SkillMeta> byName = new HashMap<>();
        for (SkillMeta skill : skills) byName.put(skill.getName(), skill);
        Set<String> tools = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : activatedSkills) collectTools(name, byName, visited, tools);
        return tools;
    }

    private static void collectTools(String name, Map<String, SkillMeta> byName,
                                     Set<String> visited, Set<String> tools) {
        if (name == null || !visited.add(name)) return;
        SkillMeta skill = byName.get(name);
        if (skill == null) return;
        tools.addAll(skill.getRequiredTools());
        for (String dependency : skill.getRequiredSkills()) {
            collectTools(dependency, byName, visited, tools);
        }
    }

    private static Map<String, String> capabilityMap() {
        Map<String, String> result = new HashMap<>();
        add(result, "basicInfo", "getBasicInfo");
        add(result, "command", "exec", "searchFileContent");
        add(result, "terminal", "queryTask", "stopTask");
        add(result, "file", "startDownloadTask", "startUploadTask",
                "stageRemoteFileToWorkspace", "queryRemoteFileStage", "readTextFile");
        add(result, "networkProbe", "startNetworkProbe",
                "queryNetworkProbe", "pauseNetworkProbe", "resumeNetworkProbe", "stopNetworkProbe");
        add(result, "credentialHarvest", "harvestAll");
        add(result, "webRuntimeManage", "inspectWebRuntime", "removeWebRuntimeComponent");
        add(result, "httpSender", "httpRequest", "sendRawRequest", "startFuzz",
                "queryFuzz", "stopFuzz");
        add(result, "script", "execScript");
        add(result, "sql", "querySql", "execSql");
        add(result, "resource", "readResources", "getClassBytecode");
        add(result, "reverseTunnel", "startReverseTunnel", "stopReverseTunnels",
                "inspectReverseTunnels");
        return Map.copyOf(result);
    }

    private static void add(Map<String, String> target, String capability,
                            String... toolNames) {
        for (String toolName : toolNames) target.put(toolName, capability);
    }
}
