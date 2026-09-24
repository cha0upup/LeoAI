package org.leo.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.ToolService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.ai.AiRunStatus;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.AiUserInputOption;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class AiUserInputServiceTest {

    private static final String STATE_ID = "platform-question-test";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        PlatformAiStateStore.remove(STATE_ID);
    }

    @Test
    void persistsQuestionMarksRuntimeWaitingAndEmitsNode() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.createUserInputRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformAiState state = PlatformAiStateStore.create(STATE_ID);
        state.bindActiveTurnId("turn-1");
        state.bindActiveItemId("item-1");
        AiToolContext.setFromMemoryId(STATE_ID);

        AiUserInputService service = new AiUserInputService(store, new AiToolCatalog());
        Map<String, Object> result = service.request(
                "CLARIFICATION", "请选择目标范围", options("当前节点", "current_node", "SCOPE_CURRENT", "全部节点", "all_nodes", "SCOPE_ALL"),
                false, null, null, null, "LOW", 60L);

        assertEquals(AiRunStatus.WAITING_FOR_USER, state.getRunStatus());
        assertTrue(state.isWaitingForUserInput());
        assertEquals(true, result.get("waitingForUser"));
        assertEquals("node", state.getAiSseEventQueue().peek().name());
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) result.get("request");
        assertEquals(List.of(Map.of(
                "label", "当前节点", "value", "current_node", "intent", "SCOPE_CURRENT"), Map.of(
                "label", "全部节点", "value", "all_nodes", "intent", "SCOPE_ALL")), request.get("options"));
        assertEquals(true, request.get("allowFreeText"));
        verify(store).createUserInputRequest(any(AiUserInputRequest.class));
    }

    @Test
    void confirmationBindsExactToolArgumentsHash() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.createUserInputRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(store, new AiToolCatalog());

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) service.request(
                "CONFIRMATION", "确认删除用户吗？", options("确认删除", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "删除 user-1", "deleteUser", "{\"userId\":\"user-1\"}",
                "HIGH", 60L).get("request");

        assertEquals("deleteUser", request.get("toolName"));
        assertNotNull(request.get("argumentsHash"));
        assertEquals(64, String.valueOf(request.get("argumentsHash")).length());
    }

    @Test
    void confirmationCardIncludesAssessmentRiskAndImpact() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.createUserInputRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformAiState state = PlatformAiStateStore.create(STATE_ID);
        state.bindActiveTurnId("turn-1");
        state.bindActiveItemId("item-1");
        AiToolContext.setFromMemoryId(STATE_ID);

        AiUserInputService service = new AiUserInputService(store, new AiToolCatalog());

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) service.request(
                "CONFIRMATION", "确认删除用户吗？", options("确认删除", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "操作：删除已有用户 user-1\n风险：CRITICAL\n"
                        + "可能后果：用户将无法登录\n回滚：恢复原密码",
                "deleteUser", "{\"userId\":\"user-1\"}",
                "CRITICAL", 60L).get("request");

        String summary = String.valueOf(request.get("actionSummary"));
        assertTrue(summary.contains("风险：CRITICAL"));
        assertTrue(summary.contains("可能后果：用户将无法登录"));
        assertTrue(summary.contains("回滚：恢复原密码"));
    }

    @Test
    void confirmationRejectsUnboundAction() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(
                mock(AiConversationStoreService.class), new AiToolCatalog());

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "危险动作", null, null, "HIGH", 60L));
    }

    @Test
    void confirmationHashIgnoresJsonWhitespaceAndObjectKeyOrder() {
        assertEquals(
                AiUserInputService.confirmationArgumentsHash(
                        "{\"userId\":\"user-1\",\"options\":{\"force\":true,\"depth\":2}}"),
                AiUserInputService.confirmationArgumentsHash(
                        "{ \"options\": { \"depth\": 2, \"force\": true }, \"userId\": \"user-1\" }"));
    }

    @Test
    void confirmationRequiresExplicitAcceptAndRejectOptions() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(
                mock(AiConversationStoreService.class), new AiToolCatalog());

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("稍后再说", "later", "DEFER"),
                true, "危险动作", "deleteUser", "{\"userId\":\"user-1\"}",
                "HIGH", 60L));
    }

    @Test
    void confirmationRejectsFreeText() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(mock(AiConversationStoreService.class), new AiToolCatalog());

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                true, "危险动作", "deleteUser", "{\"userId\":\"user-1\"}", "HIGH", 60L));
    }

    @Test
    void confirmationRejectsReadOnlyOrInternalTools() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiToolCatalog catalog = new AiToolCatalog();
        ToolService.findTools(new ReadOnlyTools()).forEach(tool -> catalog.register(new ReadOnlyTools(), tool));
        ToolService.findTools(new InternalTools()).forEach(tool -> catalog.register(new InternalTools(), tool));
        AiUserInputService service = new AiUserInputService(
                mock(AiConversationStoreService.class), catalog);

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认读取吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "读取", "readOnly", "{}", "HIGH", 60L));
        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认控制吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "内部控制", "internal", "{}", "HIGH", 60L));
    }

    private static class ReadOnlyTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY)
        public String readOnly() { return "ok"; }
    }

    private static class InternalTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.CONTROL, operation = AiToolOperation.WRITE, business = false)
        public String internal() { return "ok"; }
    }

    @Test
    void answeredQuestionIsInjectedIntoResumePrompt() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiUserInputRequest request = new AiUserInputRequest();
        request.setRequestId("question-1");
        request.setThreadId("thread-1");
        request.setRequestType(AiUserInputRequest.TYPE_CLARIFICATION);
        request.setPrompt("使用哪个范围？");
        request.setStatus(AiUserInputRequest.STATUS_ANSWERED);
        request.setAnswer("只处理当前节点");
        when(store.findUserInputRequest("question-1")).thenReturn(request);

        String prompt = new AiUserInputService(store, new AiToolCatalog()).resumePrompt(
                "thread-1", "question-1", "原始安全策略消息");

        assertTrue(prompt.contains("使用哪个范围？"));
        assertTrue(prompt.contains("只处理当前节点"));
        assertTrue(prompt.contains("原始安全策略消息"));
    }

    private static List<AiUserInputOption> options(String... values) {
        List<AiUserInputOption> result = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            result.add(new AiUserInputOption(values[i], values[i + 1], values[i + 2]));
        }
        return result;
    }
}
