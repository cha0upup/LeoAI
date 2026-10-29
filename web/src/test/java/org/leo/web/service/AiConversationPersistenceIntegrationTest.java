package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.thread.*;
import org.leo.ai.runtime.AiTurnRecoveryContext;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.dao.mapper.AiConversationMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiConversationPersistenceIntegrationTest {
    @TempDir Path temp;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AiConversationStoreService store;
    private AiTurnProtocolService protocol;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("conversations.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration mybatis = new org.apache.ibatis.session.Configuration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(AiConversationMapper.class);
        factory.setConfiguration(mybatis);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(TxConfig.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(AiConversationMapper.class, () -> template.getMapper(AiConversationMapper.class));
        context.register(AiConversationStoreService.class, AiEventJournalRepository.class,
                AiExecutionLeaseRepository.class, AiTurnTerminalRepository.class, AiProtocolTurnRepository.class,
                AiUserInputRepository.class, AiSubagentInvocationRepository.class, AiMessageRepository.class,
                AiContextCheckpointRepository.class);
        context.refresh();
        store = context.getBean(AiConversationStoreService.class);
        protocol = new AiTurnProtocolService(store);
        store.createPlatformThread("fixture-user", "fixture-session", "thread-1", "fixture", 1L, null);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void currentThreadAndSubagentMetadataRoundTripsAndPreservesForeignKeys() {
        store.createPlatformThread("fixture-user", "new-session", "new-platform-thread", "platform", 2L, null);
        var child = new org.leo.core.session.AiThread("child-thread", "child");
        child.setParentThreadId("thread-1");
        store.createAgentThread("fixture-user", "fixture-agent", "fixture-host", "child-session", child, null);
        assertEquals("thread-1", store.findThread("child-thread").getParentThreadId());
        assertEquals("fixture-host", store.findThread("child-thread").getHostId());
        assertEquals("platform", store.findThread("new-platform-thread").getScope());

        var invocation = new org.leo.core.entity.AiSubagentInvocation();
        invocation.setInvocationId("invocation-1");
        invocation.setParentThreadId("thread-1");
        invocation.setChildThreadId("child-thread");
        invocation.setTask("fixture task");
        store.insertSubagentInvocation(invocation);
        var persisted = store.listSubagentInvocations("thread-1").get(0);
        assertEquals("pending", persisted.getStatus());
        assertEquals("fixture task", persisted.getTask());
        assertEquals("child-thread", persisted.getChildThreadId());
        assertNotNull(persisted.getCreatedAt());
        assertNull(persisted.getCompletedAt());

        persisted.setStatus("completed");
        persisted.setSummary("fixture summary");
        store.updateSubagentInvocation(persisted);
        var completed = store.listSubagentInvocations("thread-1").get(0);
        assertEquals("completed", completed.getStatus());
        assertEquals("fixture summary", completed.getSummary());
        assertNotNull(completed.getCompletedAt());
        var json = com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(completed));
        assertFalse(json.containsKey("parentMessageId"));
        assertFalse(json.containsKey("inputJson"));
        assertFalse(com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(
                store.findThread("thread-1"))).containsKey("rootPlanId"));

        store.deleteThread("child-thread");
        assertNull(store.listSubagentInvocations("thread-1").get(0).getChildThreadId());
        store.deleteThread("thread-1");
        assertTrue(store.listSubagentInvocations("thread-1").isEmpty());
        assertNotNull(store.findThread("new-platform-thread"));
    }

    @Test
    void nodeThreadsArePersistedAndListedByUserAgentAndHostIncludingChildIsolation() {
        var first = new org.leo.core.session.AiThread("host-a-thread", "host a");
        store.createAgentThread("user", "agent", "host-a", "old-session", first, null);
        store.createAgentThread("user", "agent", "host-b", "session-b",
                new org.leo.core.session.AiThread("host-b-thread", "host b"), null);
        store.createAgentThread("other-user", "agent", "host-a", "other-session",
                new org.leo.core.session.AiThread("other-user-thread", "other user"), null);
        var child = new org.leo.core.session.AiThread("child", "child");
        child.setParentThreadId(first.getThreadId());
        store.createAgentThread("user", "agent", "host-a", "child-session", child, null);

        assertEquals("host-a", store.findThread("host-a-thread").getHostId());
        assertEquals(List.of("host-a-thread"), store.listAgentThreads("user", "agent", "host-a")
                .stream().map(org.leo.core.entity.AiThreadRecord::getThreadId).toList());
        assertEquals(List.of("host-b-thread"), store.listAgentThreads("user", "agent", "host-b")
                .stream().map(org.leo.core.entity.AiThreadRecord::getThreadId).toList());
        assertTrue(store.listAgentThreads("user", "agent", null).isEmpty());
        assertTrue(store.listAgentThreads("user", "other-agent", "host-a").isEmpty());
        assertEquals(List.of("thread-1"), store.listPlatformThreads("fixture-user")
                .stream().map(org.leo.core.entity.AiThreadRecord::getThreadId).toList());
        assertThrows(IllegalArgumentException.class, () -> store.createAgentThread("user", "agent", null,
                "session", new org.leo.core.session.AiThread("invalid", "invalid"), null));
    }

    @Test
    void failedTurnKeepsRecoveryPrivateWhileRestoringItForTheModel() throws Exception {
        var turn = reserve();
        var running = beginRun(turn);
        var plan = Map.of("kind", "plan", "step", "fixture step");
        String recovery = AiTurnRecoveryContext.build(List.of(), plan);
        store.discardTurn(running, "failed", "network", "fixture connection failed", "fixture connection failed",
                0, "", List.of(Map.of("kind", "plan", "seq", 1)), plan, recovery);
        protocol.completeFromRuntime(turn.id(), "failed", "fixture connection failed", null);

        var history = store.listMessages("thread-1", 0, 20);
        var assistant = history.stream().filter(message -> "assistant".equals(message.get("role"))).findFirst().orElseThrow();
        assertEquals("", assistant.get("content"));
        assertEquals("discarded", assistant.get("status"));
        assertEquals("fixture connection failed", assistant.get("protocolErrorMessage"));
        assertNotNull(assistant.get("nodes"));
        assertNotNull(assistant.get("plan"));
        assertFalse(assistant.containsKey("recoveryContext"));
        assertFalse(assistant.toString().contains("可用于继续任务"));
        assertEquals(recovery, jdbc.queryForObject("SELECT recovery_context FROM ai_messages WHERE message_id=?",
                String.class, running.assistantMessageId()));
        var memory = store.contextMessages("thread-1", 20);
        assertEquals(2, memory.size(), "the original user request must accompany a recovery-only assistant message");
        assertEquals("fixture", memory.get(0).content());
        assertEquals(recovery, memory.get(1).content());
        assertEquals(recovery, store.contextMessage("thread-1", memory.get(1).sequence()).content());

        var row = new AiMessageRecord();
        row.setContent("visible");
        row.setRecoveryContext("private memory");
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row).contains("private memory"));
    }

    @Test
    void bothHistoryPaginationPathsPreserveLiteralReplyAndKeepRecoverySeparate() {
        var turn = reserve();
        String reply = "actual partial reply\n\n" + AiTurnRecoveryContext.build(List.of(), Map.of("step", "quoted example"));
        String recovery = "private progress for model memory";
        jdbc.update("UPDATE ai_messages SET status='discarded' WHERE turn_id=?", turn.id());
        jdbc.update("UPDATE ai_messages SET content=?, recovery_context=? WHERE message_id=?", reply, recovery, turn.assistantItemId());
        for (var history : List.of(store.listMessages("thread-1", 0, 20), store.listMessagesBefore("thread-1", 3, 20))) {
            var assistant = history.stream().filter(message -> "assistant".equals(message.get("role"))).findFirst().orElseThrow();
            assertEquals(reply, assistant.get("content"));
            assertFalse(assistant.containsKey("recoveryContext"));
            assertFalse(assistant.toString().contains(recovery));
        }
        assertEquals(recovery, store.contextMessage("thread-1", 2).content());
        assertEquals(reply, jdbc.queryForObject("SELECT content FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        jdbc.update("UPDATE ai_messages SET recovery_context=NULL WHERE message_id=?", turn.assistantItemId());
        assertEquals(reply, store.contextMessage("thread-1", 2).content());
        assertEquals(reply, store.listMessagesBefore("thread-1", 3, 20).get(1).get("content"));
    }

    @Test
    void orphanRecoveryPreservesExistingTimelineAndSeparatesPrivateProgress() {
        var turn = reserve();
        var running = beginRun(turn);
        String nodes = "[{\"kind\":\"text\",\"content\":\"partial reply\"}]";
        jdbc.update("UPDATE ai_messages SET nodes_json=?, plan_json='{}', review_json='{}' WHERE message_id=?",
                nodes, turn.assistantItemId());
        context.getBean(AiEventJournalRepository.class).append("thread-1", new AiSseEvent(1, 1, "delta", "partial reply", null,
                turn.id(), turn.assistantItemId(), running.runId()), null);

        assertEquals(1, store.recoverOrphanedRuns("thread-1", 1000).size());
        assertEquals(nodes, jdbc.queryForObject("SELECT nodes_json FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        assertEquals("partial reply", jdbc.queryForObject("SELECT content FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        assertTrue(store.contextMessage("thread-1", 2).content().contains("可用于继续任务"));
        assertFalse(store.listMessages("thread-1", 0, 20).toString().contains("可用于继续任务"));
    }

    private AiConversationStoreService.PersistedTurn beginRun(AiTurnProtocolService.TurnSnapshot turn) {
        return store.beginTurn(turn.id(), turn.userItemId(), turn.assistantItemId(), "thread-1", null,
                "fixture", "fixture", null, 1L, null, AiTurnTrace.start("fixture", "thread-1", 1L), null);
    }

    @Test
    void startupFailureIsTerminalReplayableAndIdempotentWithoutARun() {
        var turn = reserve();
        assertTrue(protocol.tryStart(turn.id()).claimed());

        assertEquals("failed", protocol.failStart(turn.id(), "session expired", null).status());
        protocol.failStart(turn.id(), "session expired", null);

        assertTrue(store.listDispatchableProtocolThreadIds().isEmpty());
        assertEquals("failed", store.findThread("thread-1").getRunStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status = 'discarded'", Integer.class));
        List<AiSseEvent> events = store.listEventsAfter("thread-1", 0L, 200);
        assertEquals(1, events.size());
        assertEquals("turn/completed", events.get(0).name());
        assertEquals(turn.id(), events.get(0).turnId());
        assertEquals(turn.assistantItemId(), events.get(0).itemId());
    }

    @Test
    void startupFailureRollsBackIfTerminalEventPersistenceFails() {
        var turn = reserve();
        jdbc.execute("CREATE TRIGGER reject_event BEFORE INSERT ON ai_events BEGIN SELECT RAISE(ABORT, 'event rejected'); END");

        assertThrows(RuntimeException.class, () -> protocol.failStart(turn.id(), "expired", null));

        assertEquals("inProgress", store.findProtocolTurn(turn.id()).getProtocolStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status = 'pending'", Integer.class));
        assertEquals("idle", store.findThread("thread-1").getRunStatus());
    }

    @Test
    void unleasedStartupFailureDoesNotOverwriteARunThatHasAlreadyStarted() {
        var turn = reserve();
        jdbc.update("INSERT INTO ai_runs (run_id, thread_id, turn_id, status, started_at, trace_id) VALUES (?, ?, ?, 'running', 1, 'trace-1')",
                "run-1", "thread-1", turn.id());

        assertEquals("queued", protocol.failStart(turn.id(), "stale startup failure", null).status());

        assertEquals("running", jdbc.queryForObject("SELECT status FROM ai_runs", String.class));
        assertTrue(store.listEventsAfter("thread-1", 0L, 200).isEmpty());
    }

    @Test
    void historyCursorIsUnaffectedByNewMessagesAndRetainsProtocolMetadata() {
        var turn = reserve();
        for (int sequence = 3; sequence <= 205; sequence++) appendMessage(turn.id(), sequence);
        AiThreadQueryService query = new AiThreadQueryService(store, protocol);
        Map<String, Object> first = query.messages("thread-1", 0, 50, null);
        assertEquals(156L, first.get("nextBeforeSequence"));
        appendMessage(turn.id(), 206);

        Map<String, Object> older = query.messages("thread-1", 0, 50, 156L);
        List<?> messages = (List<?>) older.get("messages");
        assertEquals(50, messages.size());
        assertEquals(106L, ((Map<?, ?>) messages.get(0)).get("sequence"));
        assertEquals(155L, ((Map<?, ?>) messages.get(49)).get("sequence"));
        assertEquals("inProgress", ((Map<?, ?>) messages.get(0)).get("protocolStatus"));
        assertEquals(true, older.get("hasMore"));
        assertEquals(106L, older.get("nextBeforeSequence"));
        assertEquals(java.util.Set.of("messages", "nextBeforeSequence", "hasMore"), older.keySet());
        assertEquals(206, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE thread_id='thread-1'", Integer.class));
    }

    private AiTurnProtocolService.TurnSnapshot reserve() {
        return protocol.begin("thread-1", "client-1", "platform", "{}", "fixture", null, null).turn();
    }

    private void appendMessage(String turnId, int sequence) {
        jdbc.update("INSERT INTO ai_messages (message_id, thread_id, turn_id, message_seq, status, role, content, timestamp) "
                        + "VALUES (?, 'thread-1', ?, ?, 'pending', 'assistant', 'fixture', 1)",
                "message-" + sequence, turnId, sequence);
    }

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {}
}
