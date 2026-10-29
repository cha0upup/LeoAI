package org.leo.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.User;
import org.leo.core.session.AiThread;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.user.UserService;
import org.leo.service.AgentService;
import org.leo.service.security.AgentAccessService;
import org.leo.core.entity.Agent;
import org.leo.ai.tools.common.AiWorkspaceCommandTools;
import org.leo.ai.service.workspace.AiWorkspaceService;
import org.leo.ai.service.workspace.AiWorkspaceCommandService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiToolAuthorizationPolicyTest {

    private static final String PLATFORM_STATE_ID = "platform-auth-test";

    @AfterEach
    void cleanUp() {
        PlatformAiStateStore.remove(PLATFORM_STATE_ID);
        AgentNodeSessionContainer.clearAllSessions();
        AiToolContext.clear();
    }

    @Test
    void localWorkspaceCommandsAreAdministratorOnly() {
        UserService users = mock(UserService.class);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);
        AiWorkspaceCommandTools commands = new AiWorkspaceCommandTools(
                mock(AiWorkspaceService.class), mock(AiWorkspaceCommandService.class));
        for (String role : new String[]{"normal", "leader", "admin"}) {
            User caller = user("user-1", role);
            state.setExecutionPolicy(AiExecutionPolicy.from(caller));
            when(users.getUserById("user-1")).thenReturn(caller);

            Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                    AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, commands), PLATFORM_STATE_ID);

            assertEquals("admin".equals(role), tools.containsKey("workspaceExec"));
            assertEquals("admin".equals(role), tools.containsKey("workspaceExecStatus"));
            assertEquals("admin".equals(role), tools.containsKey("workspaceExecCancel"));
        }
    }

    @Test
    void withdrawnSharingBlocksAnAlreadyExposedNodeTool() {
        UserService users = mock(UserService.class);
        AgentService agents = mock(AgentService.class);
        User caller = user("reader", "normal");
        caller.setTeamId("team-a");
        when(users.getUserById("reader")).thenReturn(caller);
        Agent host = new Agent();
        host.setAgentId("host");
        host.setParentAgentId("root");
        host.setCreateByUserId("owner");
        host.setPermission("team");
        host.setTeamId("team-a");
        when(agents.findAgentById("host")).thenReturn(host);
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("session-1");
        session.setAgentId("host");
        session.setCreateByUser("reader");
        session.createAiThread("thread-1", "test").setExecutionPolicy(AiExecutionPolicy.from(caller));
        AgentNodeSessionContainer.addSession("session-1", session);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null, new AiToolCatalog(),
                new AiRuntimeResolver(), null, new AgentAccessService(agents, users));
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE, new SafeTools()),
                "session-1:thread-1").get("safeAction");
        assertTrue(tool != null);
        host.setPermission("private");

        AiToolErrorHandler errors = new AiToolErrorHandler();
        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                request("safeAction"), tool.toolExecutor(), context("session-1:thread-1"),
                errors::handleArguments, errors::handleExecution);

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("TOOL_PERMISSION_DENIED"));
    }

    @Test
    void hidesAdminToolsFromNormalUsers() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);

        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                new SafeTools(), new AdminTools()), PLATFORM_STATE_ID);

        assertTrue(tools.containsKey("safeAction"));
        assertFalse(tools.containsKey("adminAction"));
    }

    @Test
    void stoppingRuntimeInterruptsActiveToolAndItsBoundaryWorker() throws Exception {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        var boundaryExecutor = Executors.newSingleThreadExecutor();
        BlockingTools source = new BlockingTools();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users,
                new AiToolExecutionBoundary(new org.leo.ai.config.AiAgentProperties(),
                        boundaryExecutor, new AiToolResultArchive()), null);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, source), PLATFORM_STATE_ID)
                .get("blockingAction");
        Thread worker = new Thread(() -> {
            try {
                tool.toolExecutor().executeWithContext(request("blockingAction"), context(PLATFORM_STATE_ID));
                failure.complete(new AssertionError("expected interruption"));
            } catch (Throwable error) {
                failure.complete(error);
            }
        });
        try {
            worker.start();
            assertTrue(source.started.await(5, TimeUnit.SECONDS));
            state.stop("用户停止");
            Throwable error = failure.get(5, TimeUnit.SECONDS);
            assertTrue(error instanceof AiToolException);
            assertEquals("TOOL_INTERRUPTED", ((AiToolException) error).code());
            assertTrue(source.cancelled.await(5, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
            worker.join(5_000);
            boundaryExecutor.shutdownNow();
        }
    }

    public static class BlockingTools {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);

        @Tool
        public String blockingAction() throws InterruptedException {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return "unexpected";
            } catch (InterruptedException error) {
                cancelled.countDown();
                throw error;
            }
        }
    }

    @Test
    void rechecksPermissionImmediatelyBeforeExecution() {
        UserService users = mock(UserService.class);
        User admin = user("admin-1", "admin");
        User downgraded = user("admin-1", "normal");
        when(users.getUserById("admin-1")).thenReturn(admin, downgraded);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(admin));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);
        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                new AdminTools()), PLATFORM_STATE_ID);
        AiServiceTool adminTool = tools.get("adminAction");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                request("adminAction"), adminTool.toolExecutor(),
                context(PLATFORM_STATE_ID),
                errors::handleArguments, errors::handleExecution);

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("TOOL_PERMISSION_DENIED"));
    }

    @Test
    void rejectsAgentToolsWhenCallerDoesNotOwnSession() {
        UserService users = mock(UserService.class);
        User caller = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(caller);
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("other-user");
        AiThread thread = session.createAiThread("thread-1", "test");
        thread.setExecutionPolicy(AiExecutionPolicy.from(caller));
        AgentNodeSessionContainer.addSession("session-1", session);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);

        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE,
                new SafeTools()), "session-1:thread-1");

        assertTrue(tools.isEmpty());
    }

    @Test
    void destructiveToolExecutesWithoutConfirmationGate() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolCatalog catalog = new AiToolCatalog();
        register(catalog, new DestructiveTools());
        AiToolContext.setFromMemoryId(PLATFORM_STATE_ID);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null,
                catalog, new AiRuntimeResolver(), null);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                new DestructiveTools()), PLATFORM_STATE_ID).get("deleteRecord");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                request("deleteRecord", "{\"id\":1}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);

        assertFalse(result.isError());
        // 高危确认仍由工具描述和系统提示引导模型发起，但不由后端门禁强制阻断。
        assertTrue(result.resultText().contains("1"));
    }

    @Test
    void lowRiskBusinessMutationRunsWithoutSeparateAssessmentTool() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolCatalog catalog = new AiToolCatalog();
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null,
                catalog, new AiRuntimeResolver(), null);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                new MutationTools()), PLATFORM_STATE_ID).get("mutate");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult first = ToolService.executeWithErrorHandling(
                request("mutate", "{\"value\":\"one\"}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);
        assertFalse(first.isError());

        ToolExecutionResult accepted = ToolService.executeWithErrorHandling(
                request("mutate", "{\"value\":\"one\"}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);
        assertFalse(accepted.isError());
        assertTrue(accepted.resultText().contains("one"));
    }

    @Test
    void exposesRiskPreconditionInControlledToolDescription() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);

        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                new MutationTools()), PLATFORM_STATE_ID);

        String description = tools.get("mutate").toolSpecification().description();
        assertTrue(description.contains("当前决策中判断本次具体参数的风险"));
        assertTrue(description.contains("request_user_input(type=CONFIRMATION)"));
        assertTrue(description.contains("低风险操作无需询问用户"));
    }

    private static Map<String, AiServiceTool> providedTools(
            ToolProvider provider, String memoryId) {
        ToolProviderRequest request = ToolProviderRequest.builder()
                .invocationContext(context(memoryId))
                .userMessage(UserMessage.from("test"))
                .build();
        return provider.provideTools(request).aiServiceTools().stream()
                .collect(Collectors.toMap(AiServiceTool::name, Function.identity()));
    }

    private static void register(AiToolCatalog catalog, Object source) {
        ToolService.findTools(source).forEach(tool -> catalog.register(source, tool));
    }

    private static InvocationContext context(String memoryId) {
        return InvocationContext.builder().chatMemoryId(memoryId).build();
    }

    private static ToolExecutionRequest request(String name) {
        return request(name, "{}");
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-" + name)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private static User user(String userId, String privilege) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userId);
        user.setPrivilege(privilege);
        user.setStatus(1);
        return user;
    }

    private static class SafeTools {
        @Tool
        public String safeAction() {
            return "ok";
        }
    }

    private static class DestructiveTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND,
                operation = AiToolOperation.DESTRUCTIVE)
        public String deleteRecord(int id) {
            return "deleted-" + id;
        }
    }

    private static class MutationTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND,
                operation = AiToolOperation.WRITE)
        public String mutate(String value) {
            return value;
        }
    }

    @AiToolAccess(AiToolAccess.Level.ADMIN)
    private static class AdminTools {
        @Tool
        public String adminAction() {
            return "admin";
        }
    }
}
