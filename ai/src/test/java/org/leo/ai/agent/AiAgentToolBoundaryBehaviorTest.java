package org.leo.ai.agent;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.testing.ScriptedChatModel;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.User;
import org.leo.service.user.UserService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.leo.ai.testing.ScriptedChatModel.response;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 Agent 工具循环对统一执行边界的确定性回归场景。 */
class AiAgentToolBoundaryBehaviorTest {

    private static final String MEMORY_ID = "platform-tool-boundary-test";
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
        PlatformAiStateStore.remove(MEMORY_ID);
        AiToolContext.clear();
    }

    @Test
    void repeatedWriteCallIdExecutesOnlyOnce() {
        ToolExecutionRequest duplicate = request(
                "same-call", "createBoundaryRecord", "{}");
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCalls(duplicate, duplicate)
                .then(chatRequest -> {
                    List<String> results = toolResults(chatRequest.messages());
                    assertEquals(2, results.size());
                    assertTrue(results.stream().allMatch(
                            result -> result.contains("\"protocol\":\"leo.tool.result.v1\"")));
                    assertTrue(results.stream().anyMatch(
                            result -> result.contains("\"deduplicated\":true")));
                    return response(dev.langchain4j.data.message.AiMessage.from("只执行了一次"));
                });
        BoundaryTools tools = new BoundaryTools();
        BoundaryAgent agent = agent(model, tools, 5_000, 4_000);

        assertEquals("只执行了一次", agent.chat(MEMORY_ID, "创建记录"));
        assertEquals(1, tools.writeCalls.get());
        model.assertExhausted();
    }

    @Test
    void readTimeoutIsReturnedAsRetryableProtocolFailure() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("slow-read", "getBoundarySlow", "{}")
                .then(chatRequest -> {
                    String error = lastToolResult(chatRequest.messages());
                    assertTrue(error.contains("\"code\":\"TOOL_TIMEOUT\""));
                    assertTrue(error.contains("\"retryable\":true"));
                    assertTrue(error.contains("\"recoverableBy\":\"MODEL\""));
                    return response(dev.langchain4j.data.message.AiMessage.from("查询超时，已停止重试"));
                });
        BoundaryAgent agent = agent(model, new BoundaryTools(), 20, 4_000);

        assertEquals("查询超时，已停止重试",
                agent.chat(MEMORY_ID, "执行慢查询"));
        model.assertExhausted();
    }

    @Test
    void largeResultCanBePagedFromSessionScopedArchive() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("large-read", "getBoundaryLarge", "{}")
                .then(chatRequest -> {
                    Map<String, Object> envelope = json(lastToolResult(chatRequest.messages()));
                    Map<String, Object> metadata = metadata(envelope);
                    assertEquals(true, metadata.get("truncated"));
                    String archiveId = String.valueOf(metadata.get("archiveId"));
                    return response(dev.langchain4j.data.message.AiMessage.from(
                            request("archive-read", "get_tool_result_archive",
                                    "{\"archiveId\":\"" + archiveId
                                            + "\",\"offset\":0,\"limit\":200}")));
                })
                .then(chatRequest -> {
                    Map<String, Object> envelope = json(lastToolResult(chatRequest.messages()));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) envelope.get("data");
                    assertEquals("x".repeat(200), data.get("content"));
                    assertEquals(true, data.get("hasMore"));
                    return response(dev.langchain4j.data.message.AiMessage.from("已分页读取大型结果"));
                });
        BoundaryAgent agent = agent(model, new BoundaryTools(), 5_000, 1_024);

        assertEquals("已分页读取大型结果",
                agent.chat(MEMORY_ID, "读取大型报告"));
        model.assertExhausted();
    }

    @Test
    void longDynamicToolLoopKeepsTheActiveUserMessage() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("write-1", "createBoundaryRecord", "{}")
                .thenToolCall("write-2", "createBoundaryRecord", "{}")
                .then(chatRequest -> {
                    assertEquals("连续执行工具",
                            dev.langchain4j.data.message.UserMessage
                                    .findLast(chatRequest.messages())
                                    .orElseThrow()
                                    .singleText());
                    return response(dev.langchain4j.data.message.AiMessage.from("已完成"));
                });
        BoundaryTools tools = new BoundaryTools();
        BoundaryAgent agent = agent(model, tools, 5_000, 4_000, 3);

        assertEquals("已完成", agent.chat(MEMORY_ID, "连续执行工具"));
        assertEquals(2, tools.writeCalls.get());
        model.assertExhausted();
    }

    private BoundaryAgent agent(ScriptedChatModel model,
                                BoundaryTools tools,
                                long timeoutMs,
                                int maxResultChars) {
        return agent(model, tools, timeoutMs, maxResultChars, 30);
    }

    private BoundaryAgent agent(ScriptedChatModel model,
                                BoundaryTools tools,
                                long timeoutMs,
                                int maxResultChars,
                                int maxMessages) {
        User user = new User();
        user.setUserId("user-1");
        user.setUserName("user-1");
        user.setPrivilege("normal");
        user.setStatus(1);
        UserService users = mock(UserService.class);
        when(users.getUserById("user-1")).thenReturn(user);
        PlatformAiState state = PlatformAiStateStore.create(MEMORY_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(user));

        AiAgentProperties properties = new AiAgentProperties();
        properties.getPlatform().getMain().setToolTimeoutMs(timeoutMs);
        properties.getPlatform().getMain().setMaxToolResultChars(maxResultChars);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        executors.add(executor);
        AiToolResultArchive archive = new AiToolResultArchive();
        AiToolExecutionBoundary boundary = new AiToolExecutionBoundary(
                properties, executor, archive);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, boundary, new AiToolResultArchiveTools(archive));
        ToolProvider provider = policy.toolProvider(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, tools);
        AiToolErrorHandler errors = new AiToolErrorHandler();

        return AiServices.builder(BoundaryAgent.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> new ActiveUserPreservingChatMemory(
                        MessageWindowChatMemory.builder()
                                .id(memoryId).maxMessages(maxMessages).build()))
                .toolProvider(provider)
                .toolArgumentsErrorHandler(errors::handleArguments)
                .toolExecutionErrorHandler(errors::handleExecution)
                .hallucinatedToolNameStrategy(errors::handleUnknownTool)
                .build();
    }

    private static ToolExecutionRequest request(
            String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build();
    }

    private static List<String> toolResults(List<ChatMessage> messages) {
        List<String> results = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result) {
                results.add(result.text());
            }
        }
        return results;
    }

    private static String lastToolResult(List<ChatMessage> messages) {
        List<String> results = toolResults(messages);
        if (results.isEmpty()) throw new AssertionError("没有工具结果");
        return results.get(results.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String value) {
        return JSON.parseObject(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("metadata");
    }

    private interface BoundaryAgent {
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }

    @AiToolPolicy(kind = AiToolKind.COMMAND,
            operation = AiToolOperation.WRITE,
            business = false)
    private static final class BoundaryTools {
        private final AtomicInteger writeCalls = new AtomicInteger();

        @Tool
        public String createBoundaryRecord() {
            return "record-" + writeCalls.incrementAndGet();
        }

        @Tool
        @AiToolPolicy(kind = AiToolKind.QUERY,
                operation = AiToolOperation.READ_ONLY,
                parallelizable = true)
        public String getBoundarySlow() {
            try {
                Thread.sleep(1_000);
                return "late";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        }

        @Tool
        @AiToolPolicy(kind = AiToolKind.QUERY,
                operation = AiToolOperation.READ_ONLY,
                parallelizable = true)
        public String getBoundaryLarge() {
            return "x".repeat(5_000);
        }
    }
}
