package org.leo.web.service;

import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.user.UserService;
import org.leo.web.security.PermissionService;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformPuppetAiBridgeToolsTest {

    private static final String PARENT_THREAD_ID = "platform-ai-test";

    @AfterEach
    void cleanStaticStores() {
        PlatformAiStateStore.remove(PARENT_THREAD_ID);
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @Test
    void rejectsMismatchedPlatformThreadOwner() {
        Fixture fixture = fixture("other-user");

        assertThrows(SecurityException.class,
                () -> fixture.tools.listTargets(PARENT_THREAD_ID));
    }

    @Test
    void dispatchesIntoIsolatedChildThreadAndReturnsSummary() {
        Fixture fixture = fixture("user-1");
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setPuppetId("puppet-1");
        PuppetNodeSessionContainer.addSession(session.getSessionId(), session);

        when(fixture.puppetAiService.createChildThread(
                eq(session), any(), eq(7), eq(PARENT_THREAD_ID)))
                .thenAnswer(invocation -> {
                    var thread = session.createAiThread("child-1", "child");
                    thread.setParentThreadId(PARENT_THREAD_ID);
                    thread.setAiConfigId(7);
                    return Map.of("threadId", "child-1");
                });
        when(fixture.delegationService.execute(
                eq(session), any(), eq("检查当前身份"), any(), any(), any(), any(), eq(fixture.state)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<AiSseEvent> sink = invocation.getArgument(6, Consumer.class);
                    sink.accept(new AiSseEvent(3L, System.currentTimeMillis(), "node",
                            Map.of("kind", "tool", "toolName", "exec"),
                            String.valueOf((Object) invocation.getArgument(5)),
                            null, null, null));
                    return Map.of("status", "completed", "summary", "当前用户为 root");
                });

        Map<String, Object> result = fixture.tools.dispatch(
                PARENT_THREAD_ID, "检查当前身份", "session-1", null, null);

        assertEquals(AiSubagentInvocation.STATUS_COMPLETED, result.get("status"), String.valueOf(result));
        assertEquals("child-1", result.get("childThreadId"));
        assertEquals("当前用户为 root", result.get("summary"));
        assertNotNull(result.get("invocationId"));
        verify(fixture.conversationStore).insertSubagentInvocation(any());
        verify(fixture.conversationStore, times(2)).updateSubagentInvocation(any());
        verify(fixture.permissionService).requireSessionAccess(session, fixture.user, "session-1");
    }

    @Test
    void waitingChildDoesNotReceiveCompletionTimestamp() {
        Fixture fixture = fixture("user-1");
        PuppetNodeSession session = prepareChild(fixture);
        when(fixture.delegationService.execute(
                eq(session), any(), any(), any(), any(), any(), any(), eq(fixture.state)))
                .thenReturn(Map.of("status", "waiting_for_user", "summary", ""));

        Map<String, Object> result = fixture.tools.dispatch(
                PARENT_THREAD_ID, "检查", "session-1", null, null);

        assertEquals("waiting_for_user", result.get("status"));
        var invocation = org.mockito.ArgumentCaptor.forClass(AiSubagentInvocation.class);
        verify(fixture.conversationStore, times(2)).updateSubagentInvocation(invocation.capture());
        assertEquals("waiting_for_user", invocation.getValue().getStatus());
        assertNull(invocation.getValue().getCompletedAt());
    }

    @Test
    void cancellationIsReportedSeparatelyFromFailure() {
        Fixture fixture = fixture("user-1");
        PuppetNodeSession session = prepareChild(fixture);
        when(fixture.delegationService.execute(
                eq(session), any(), any(), any(), any(), any(), any(), eq(fixture.state)))
                .thenAnswer(call -> {
                    session.getAiThread("child-1").markCancelled();
                    throw new IllegalStateException("用户停止");
                });

        Map<String, Object> result = fixture.tools.dispatch(
                PARENT_THREAD_ID, "检查", "session-1", null, null);

        assertEquals("cancelled", result.get("status"));
    }

    @Test
    void unknownDelegationStatusFailsInsteadOfClaimingSuccess() {
        Fixture fixture = fixture("user-1");
        PuppetNodeSession session = prepareChild(fixture);
        when(fixture.delegationService.execute(
                eq(session), any(), any(), any(), any(), any(), any(), eq(fixture.state)))
                .thenReturn(Map.of("status", "running"));

        Map<String, Object> result = fixture.tools.dispatch(
                PARENT_THREAD_ID, "检查", "session-1", null, null);

        assertEquals("failed", result.get("status"));
    }

    private PuppetNodeSession prepareChild(Fixture fixture) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setPuppetId("puppet-1");
        PuppetNodeSessionContainer.addSession(session.getSessionId(), session);
        when(fixture.puppetAiService.createChildThread(
                eq(session), any(), eq(7), eq(PARENT_THREAD_ID)))
                .thenAnswer(call -> {
                    session.createAiThread("child-1", "child");
                    return Map.of("threadId", "child-1");
                });
        return session;
    }

    @Test
    void dispatchSchemaRequiresOnlyTheTaskSelectorPayload() {
        Fixture fixture = fixture("user-1");
        var specification = ToolService.findTools(fixture.tools).stream()
                .filter(tool -> "dispatch_puppet_ai".equals(tool.name()))
                .findFirst().orElseThrow().toolSpecification();

        assertTrue(specification.parameters().required().contains("task"));
        assertFalse(specification.parameters().required().contains("sessionId"));
        assertFalse(specification.parameters().required().contains("puppetId"));
        assertFalse(specification.parameters().required().contains("configId"));
    }

    private Fixture fixture(String recordOwner) {
        PuppetNodeAiThreadService puppetAiService = mock(PuppetNodeAiThreadService.class);
        PuppetNodeAiDelegationService delegationService =
                mock(PuppetNodeAiDelegationService.class);
        PuppetNodeLifecycleService lifecycleService = mock(PuppetNodeLifecycleService.class);
        PermissionService permissionService = mock(PermissionService.class);
        UserService userService = mock(UserService.class);
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        AiAuditLogStore auditLogStore = new AiAuditLogStore();

        User user = new User();
        user.setUserId("user-1");
        user.setUserName("alice");
        user.setPrivilege("normal");
        user.setStatus(1);
        when(userService.getUserById("user-1")).thenReturn(user);

        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(PARENT_THREAD_ID);
        record.setScope(AiConversationStoreService.SCOPE_PLATFORM);
        record.setUserId(recordOwner);
        when(conversationStore.findThread(PARENT_THREAD_ID)).thenReturn(record);

        PlatformAiState state = PlatformAiStateStore.create(PARENT_THREAD_ID);
        state.setAiConfigId(7);
        state.setExecutionPolicy(new AiExecutionPolicy("user-1", "alice", "normal"));

        PlatformPuppetAiBridgeTools tools = new PlatformPuppetAiBridgeTools(
                puppetAiService, delegationService, lifecycleService,
                permissionService, userService,
                conversationStore, auditLogStore);
        return new Fixture(tools, puppetAiService, delegationService,
                permissionService, conversationStore, user, state);
    }

    private record Fixture(PlatformPuppetAiBridgeTools tools,
                           PuppetNodeAiThreadService puppetAiService,
                           PuppetNodeAiDelegationService delegationService,
                           PermissionService permissionService,
                           AiConversationStoreService conversationStore,
                           User user,
                           PlatformAiState state) {}
}
