package org.leo.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.Test;
import org.leo.ai.tools.common.AgentWorkspaceCommandTools;
import org.leo.ai.tools.common.AgentWorkspaceTools;
import org.leo.ai.tools.common.PlanTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.common.WebResearchTools;
import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.PuppetTools;
import org.leo.ai.tools.platform.ShellGeneratorTools;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.ai.tools.puppetnode.BasicInfoTools;
import org.leo.ai.tools.puppetnode.CommandTools;
import org.leo.ai.tools.puppetnode.CredentialHarvestTools;
import org.leo.ai.tools.puppetnode.DatabaseConnectionTools;
import org.leo.ai.tools.puppetnode.FileTools;
import org.leo.ai.tools.puppetnode.HttpRequestTools;
import org.leo.ai.tools.puppetnode.JavaPluginTools;
import org.leo.ai.tools.puppetnode.ResourceTools;
import org.leo.ai.tools.puppetnode.ReverseTunnelTools;
import org.leo.ai.tools.puppetnode.ScanTools;
import org.leo.ai.tools.puppetnode.ScriptTools;
import org.leo.ai.tools.puppetnode.SqlTools;
import org.leo.ai.tools.puppetnode.WebRuntimeTools;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.ai.service.AiUserInputService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 防止工具清单重新出现重名、无风险声明或无预算增长。 */
class AiToolCatalogContractTest {

    private static final List<Class<?>> TOOL_CLASSES = List.of(
            AgentWorkspaceCommandTools.class, AgentWorkspaceTools.class, PlanTools.class,
            UserInputTools.class, WebResearchTools.class,
            DisguiseTools.class, FingerprintTools.class, PluginTools.class,
            PuppetTools.class, ShellGeneratorTools.class, SkillActivationTools.class,
            TeamTools.class, UserTools.class,
            BasicInfoTools.class,
            CommandTools.class, CredentialHarvestTools.class,
            DatabaseConnectionTools.class, FileTools.class, HttpRequestTools.class,
            JavaPluginTools.class, ResourceTools.class, ReverseTunnelTools.class,
            ScanTools.class, ScriptTools.class, SqlTools.class, WebRuntimeTools.class,
            AiToolResultArchiveTools.class
    );

    private static final Set<String> REMOVED_TOOL_NAMES = Set.of(
            "getAllTeam", "getAllTeamName", "getTeamById", "getTeamByName",
            "getTeamsByLeader", "getAllUser", "getAllNoTeamUser", "getAllUserName",
            "getUserById", "getUserByName", "getPrivileges", "getAllPuppet",
            "getPuppetsByCreateUserId", "getPuppetsByParentPuppetId",
            "getPuppetsByPermission",
            "getPlugins", "getPluginsByType", "getFingerprints",
            "getFingerprintsByProtocol", "getJavaPlugins", "getJavaPluginsByType",
            "getResource", "readSpringBootConfigResources", "readResourceCandidates",
            "workspaceStat", "symmetricCrypto", "rsaCrypto", "manage_recon_summary",
            "stopReverseTunnel", "stopAllReverseTunnels", "listReverseTunnels",
            "getReverseTunnelStatistics",
            "readClipboard", "writeClipboard", "monitorClipboard"
    );

    @Test
    void toolInventoryHasUniqueNamesExplicitPoliciesAndFixedBudget() {
        List<Declaration> declarations = declarations();
        Set<String> names = new HashSet<>();

        assertEquals(101, declarations.size(),
                "工具预算发生变化；新增前应优先合并，并显式更新清单契约");
        for (Declaration declaration : declarations) {
            assertTrue(names.add(declaration.name()),
                    "工具名重复: " + declaration.name());
            AiToolPolicy policy = declaration.policy();
            assertNotNull(policy, "工具缺少 AiToolPolicy: " + declaration.name());
            if (policy.operation() == AiToolOperation.READ_ONLY) {
                assertTrue(policy.parallelizable(),
                        "只读工具必须允许并行: " + declaration.name());
            }
            if (policy.operation() == AiToolOperation.DESTRUCTIVE) {
                assertTrue(policy.exclusive(),
                        "破坏性工具必须独占执行: " + declaration.name());
            }
        }
        assertTrue(names.contains("getDatabaseDialectCatalog"),
                "数据库配置必须暴露权威方言目录，避免模型创造方言 ID");
        for (String removed : REMOVED_TOOL_NAMES) {
            assertFalse(names.contains(removed), "已清理工具被重新暴露: " + removed);
        }
    }

    @Test
    void controlToolSchemasExposeComplexFieldsAndRealOptionality() {
        AiServiceTool inputTool = namedTool(
                ToolService.findTools(new UserInputTools(mock(AiUserInputService.class))),
                "request_user_input");
        List<String> inputRequired = inputTool.toolSpecification().parameters().required();
        assertEquals(List.of("prompt"), inputRequired);

        AiServiceTool planTool = namedTool(ToolService.findTools(new PlanTools(
                mock(AgentRuntimeResolver.class), mock(AiPlanCoordinator.class))), "createPlan");
        JsonObjectSchema parameters = planTool.toolSpecification().parameters();
        assertEquals(Set.of("title", "goal", "steps"), Set.copyOf(parameters.required()));
        JsonArraySchema steps = assertInstanceOf(
                JsonArraySchema.class, parameters.properties().get("steps"));
        JsonObjectSchema step = assertInstanceOf(JsonObjectSchema.class, steps.items());
        assertEquals(Set.of("description", "toolHint", "parallel", "successCriteria",
                        "maxRetries", "dependsOn"), step.properties().keySet());
        assertEquals(List.of("description"), step.required());
        assertTrue(step.properties().values().stream()
                .allMatch(property -> property.description() != null
                        && !property.description().isBlank()));
    }

    @Test
    void terminalLifecycleToolsAreRoutineControlOperations() {
        Declaration query = declarations().stream()
                .filter(declaration -> "queryTask".equals(declaration.name()))
                .findFirst().orElseThrow();
        assertEquals(AiToolKind.QUERY, query.policy().kind());
        assertEquals(AiToolOperation.WRITE, query.policy().operation());
        assertFalse(query.policy().business());
        assertFalse(query.policy().terminal());
        assertTrue(query.policy().exclusive());

        for (String toolName : List.of("writeTask", "resizeTask", "stopTask")) {
            Declaration tool = declarations().stream()
                    .filter(declaration -> toolName.equals(declaration.name()))
                    .findFirst().orElseThrow();

            assertEquals(AiToolKind.CONTROL, tool.policy().kind());
            assertEquals(AiToolOperation.WRITE, tool.policy().operation());
            assertFalse(tool.policy().business());
            assertFalse(tool.policy().terminal());
            assertTrue(tool.policy().exclusive());
        }
    }

    private static AiServiceTool namedTool(List<AiServiceTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private static List<Declaration> declarations() {
        List<Declaration> result = new ArrayList<>();
        for (Class<?> type : TOOL_CLASSES) {
            AiToolPolicy classPolicy = type.getAnnotation(AiToolPolicy.class);
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) continue;
                String name = tool.name() == null || tool.name().isBlank()
                        ? method.getName() : tool.name();
                AiToolPolicy methodPolicy = method.getAnnotation(AiToolPolicy.class);
                result.add(new Declaration(name,
                        methodPolicy != null ? methodPolicy : classPolicy));
            }
        }
        return result;
    }

    private record Declaration(String name, AiToolPolicy policy) {
    }
}
