package org.leo.web.application.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.runtime.AiTurnRecoveryContext;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.thread.*;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.leo.web.testing.SqliteTestDatabase;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiConversationPersistenceIntegrationTest {
    @TempDir Path temp;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AiConversationStoreService store;
    private AiTurnTerminalRepository terminal;
    private AiEventJournalRepository journal;
    private AiMessageRepository messages;
    private AiExecutionLeaseRepository leases;
    private AiUserInputRepository requests;
    private AiProtocolTurnRepository turns;
    private AiTurnProtocolService protocol;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = SqliteTestDatabase.create(temp.resolve("conversations.db"));
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
                AiContextCheckpointRepository.class, AiTurnProtocolService.class);
        context.refresh();
        store = context.getBean(AiConversationStoreService.class);
        terminal = context.getBean(AiTurnTerminalRepository.class);
        journal = context.getBean(AiEventJournalRepository.class);
        messages = context.getBean(AiMessageRepository.class);
        leases = context.getBean(AiExecutionLeaseRepository.class);
        requests = context.getBean(AiUserInputRepository.class);
        turns = context.getBean(AiProtocolTurnRepository.class);
        protocol = context.getBean(AiTurnProtocolService.class);
        store.createPlatformThread("fixture-user", "fixture-session", "thread-1", "fixture", 1L, null);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void reattachedRuntimesContinueTheJournalAndReplayRoutingMetadata() {
        var turn = reserve();
        var running = beginRun(turn);
        for (int sequence = 1; sequence <= 2; sequence++) {
            var runtime = new org.leo.core.session.AiThread("thread-1", "fixture");
            runtime.bindActiveTurnId(turn.id());
            runtime.bindActiveItemId(turn.assistantItemId());
            runtime.bindActiveRunId(running.runId());
            journal.attach("thread-1", runtime);

            assertEquals(sequence, runtime.recordSseEvent("delta", Map.of("sequence", sequence)).seq());
        }

        List<AiSseEvent> replay = journal.listAfter("thread-1", 1L, 200);
        assertEquals(1, replay.size());
        AiSseEvent event = replay.get(0);
        assertEquals(2L, event.seq());
        assertEquals(turn.id(), event.turnId());
        assertEquals(turn.assistantItemId(), event.itemId());
        assertEquals(running.runId(), event.runId());
        assertEquals(Map.of("sequence", 2), event.data());
        assertEquals(2L, journal.lastSequence("thread-1"));
    }

    @Test
    void replayStatusTracksOnlyTheLatestStartedTurn() {
        String threadId = "thread-1";
        assertEquals(0L, journal.latestTurnStartSequence(threadId));
        assertTrue(journal.hasLatestTurnCompleted(threadId));

        journal.append(threadId, new AiSseEvent(1, 1, "turn/started", null,
                null, "earlier-turn", null, null), null);
        journal.append(threadId, new AiSseEvent(2, 2, "turn/completed", null,
                null, "earlier-turn", null, null), null);
        assertTrue(journal.hasTurnCompleted(threadId, "earlier-turn"));
        assertTrue(journal.hasLatestTurnCompleted(threadId));

        journal.append(threadId, new AiSseEvent(3, 3, "turn/started", null,
                null, "current-turn", null, null), null);
        assertEquals(2L, journal.latestTurnStartSequence(threadId));
        assertFalse(journal.hasLatestTurnCompleted(threadId));

        journal.append(threadId, new AiSseEvent(4, 4, "turn/completed", null,
                null, "current-turn", null, null), null);
        assertTrue(journal.hasLatestTurnCompleted(threadId));
    }

    @Test
    void pendingQuestionIsReusedUntilItExpires() {
        requests.create(confirmation("question-1"));
        AiUserInputRequest next = confirmation("question-2");

        assertEquals("question-1", requests.create(next).getRequestId());
        assertNull(requests.find("question-2"));
        jdbc.update("UPDATE ai_user_input_requests SET expires_at=1 WHERE request_id='question-1'");

        var snapshot = protocol.snapshotThread("thread-1", "waiting_for_user");
        assertNull(snapshot.pendingUserInput());
        assertEquals("completed", snapshot.status());
        assertEquals(AiUserInputRequest.STATUS_PENDING, requests.find("question-1").getStatus());
        assertEquals("question-2", requests.create(next).getRequestId());
        assertEquals(AiUserInputRequest.STATUS_EXPIRED, requests.find("question-1").getStatus());
        assertEquals("question-2", requests.findPending("thread-1").getRequestId());
    }

    @ParameterizedTest
    @CsvSource({"other, false", "'', false", "confirm, true"})
    void invalidAnswersRollBackReservedTurnAndBothMessages(String answer, boolean expired) {
        AiUserInputRequest question = confirmation("question-1");
        if (expired) question.setExpiresAt(1L);
        requests.create(question);

        assertThrows(IllegalStateException.class, () -> protocol.begin(
                "thread-1", "answer-client", "platform", "{}", answer, null, "question-1"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_turns", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages", Integer.class));
        assertEquals(AiUserInputRequest.STATUS_PENDING, requests.find("question-1").getStatus());
        jdbc.update("UPDATE ai_user_input_requests SET expires_at=? WHERE request_id='question-1'", Long.MAX_VALUE);

        var retried = protocol.begin(
                "thread-1", "answer-client", "platform", "{}", "confirm", null, "question-1");

        assertFalse(retried.reused());
        assertEquals(AiUserInputRequest.STATUS_ANSWERED, requests.find("question-1").getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_turns", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages", Integer.class));
    }

    @Test
    void pendingQuestionBlocksOrdinaryTurnsAndAnswerTakesPriority() {
        var ordinary = reserve();
        requests.create(confirmation("question-1"));

        assertNull(protocol.findNextQueued("thread-1"));
        assertTrue(protocol.listDispatchableThreadIds().isEmpty());
        assertFalse(protocol.tryStart(ordinary.id()).claimed());

        var answer = protocol.begin(
                "thread-1", "answer-client", "platform", "{}", "confirm", null, "question-1").turn();

        assertEquals(answer.id(), protocol.findNextQueued("thread-1").id());
        assertEquals(List.of("thread-1"), protocol.listDispatchableThreadIds());
        assertFalse(protocol.tryStart(ordinary.id()).claimed());
        assertTrue(protocol.tryStart(answer.id()).claimed());
    }

    @Test
    void matchingConfirmationCanBeConsumedOnlyOnceAcrossConcurrentCallers() throws Exception {
        requests.create(confirmation("question-1"));
        protocol.begin("thread-1", "answer-client", "platform", "{}", "confirm", null, "question-1");
        long consumedAt = System.currentTimeMillis();
        assertFalse(requests.consumeConfirmation("question-1", "other-thread", "updateProfile", "fixture-hash", consumedAt));
        assertFalse(requests.consumeConfirmation("question-1", "thread-1", "otherTool", "fixture-hash", consumedAt));
        assertFalse(requests.consumeConfirmation("question-1", "thread-1", "updateProfile", "other-hash", consumedAt));
        assertEquals(1, concurrentSuccesses(() -> requests.consumeConfirmation(
                "question-1", "thread-1", "updateProfile", "fixture-hash", consumedAt)));
        assertEquals(consumedAt, requests.find("question-1").getConfirmationConsumedAt());
    }

    @Test
    void queuedTurnsHaveExclusiveClaimsAndCanBeRequeuedBeforeRunning() throws Exception {
        var turn = reserve();
        assertEquals(turn.id(), protocol.findNextQueued("thread-1").id());
        assertEquals(List.of("thread-1"), protocol.listDispatchableThreadIds());

        assertEquals(1, concurrentSuccesses(() -> protocol.tryStart(turn.id()).claimed()));

        assertTrue(protocol.listDispatchableThreadIds().isEmpty());
        assertThrows(IllegalStateException.class, () -> protocol.completeFromRuntime(
                turn.id(), "completed", null, "stale-lease"));
        assertEquals("running", turns.find(turn.id()).getDispatchStatus());
        assertEquals("queued", protocol.requeue(turn.id()).status());
        assertNull(turns.find(turn.id()).getStartedAt());
        assertTrue(protocol.tryStart(turn.id()).claimed());
        var running = beginRun(turn);
        terminal.complete(running, "fixture complete", List.of(), null, null, 0);

        assertEquals("completed", protocol.completeFromRuntime(turn.id(), "completed", null, null).status());
        assertTrue(turns.listInProgress("thread-1").isEmpty());
        assertNull(protocol.findNextQueued("thread-1"));
        assertTrue(messages.list("thread-1", 0, 20).stream()
                .allMatch(message -> "committed".equals(message.get("status"))));
    }

    @Test
    void queuedCancellationDiscardsMessagesAndAppendsOneReplayableCompletion() {
        var turn = reserve();

        var cancelled = protocol.requestInterrupt("thread-1", turn.id());
        protocol.requestInterrupt("thread-1", turn.id());

        assertEquals("interrupted", cancelled.status());
        assertTrue(cancelled.interruptRequested());
        assertTrue(protocol.listDispatchableThreadIds().isEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status='discarded'", Integer.class));
        List<AiSseEvent> events = journal.listAfter("thread-1", 0, 200);
        assertEquals(1, events.size());
        assertEquals("turn/completed", events.get(0).name());
        assertEquals(turn.id(), events.get(0).turnId());
        assertEquals(turn.assistantItemId(), events.get(0).itemId());
    }

    @Test
    void queuedCancellationRollsBackWhenCompletionEventCannotBeWritten() {
        var turn = reserve();
        jdbc.execute("CREATE TRIGGER reject_event BEFORE INSERT ON ai_events BEGIN SELECT RAISE(ABORT, 'event rejected'); END");

        assertThrows(RuntimeException.class, () -> protocol.requestInterrupt("thread-1", turn.id()));

        assertEquals("queued", turns.find(turn.id()).getDispatchStatus());
        assertFalse(turns.find(turn.id()).getInterruptRequested());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status='pending'", Integer.class));
        assertTrue(journal.listAfter("thread-1", 0, 200).isEmpty());
    }

    @Test
    void contextCheckpointsRoundTripIndependentlyOfTurnStorage() {
        var checkpoints = context.getBean(AiContextCheckpointRepository.class);
        assertNull(checkpoints.find("thread-1"));
        checkpoints.clear("thread-1");
        assertEquals(1L, store.findThread("thread-1").getLastActiveAt());

        checkpoints.update("thread-1", "summary", 42L, "hash-42", 2);

        assertEquals(new ConversationCheckpoint("summary", 42L, "hash-42", 2), checkpoints.find("thread-1"));
        assertNull(store.findThread("thread-1").getContextSummary());
        assertNull(store.listPlatformThreads("fixture-user").get(0).getContextCheckpointJson());
        jdbc.update("UPDATE ai_threads SET last_active_at=2 WHERE thread_id='thread-1'");
        checkpoints.update("thread-1", "summary", 42L, "hash-42", 2);
        assertEquals(2L, store.findThread("thread-1").getLastActiveAt());
        checkpoints.update("thread-1", "summary", 43L, "hash-43", 2);
        assertEquals(new ConversationCheckpoint("summary", 43L, "hash-43", 2), checkpoints.find("thread-1"));
        assertTrue(store.findThread("thread-1").getLastActiveAt() > 2L);
        checkpoints.clear("thread-1");
        assertNull(checkpoints.find("thread-1"));
        var cleared = jdbc.queryForMap(
                "SELECT context_summary, context_checkpoint_json FROM ai_threads WHERE thread_id='thread-1'");
        assertNull(cleared.get("context_summary"));
        assertNull(cleared.get("context_checkpoint_json"));
        assertTrue(messages.list("thread-1", 0, 20).isEmpty());
    }

    @Test
    void currentThreadAndSubagentMetadataRoundTripsAndPreservesForeignKeys() {
        var invocations = context.getBean(AiSubagentInvocationRepository.class);
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
        invocations.insert(invocation);
        var persisted = invocations.listByParentThread("thread-1").get(0);
        assertEquals("pending", persisted.getStatus());
        assertEquals("fixture task", persisted.getTask());
        assertEquals("child-thread", persisted.getChildThreadId());
        assertNotNull(persisted.getCreatedAt());
        assertNull(persisted.getCompletedAt());

        persisted.setStatus("completed");
        persisted.setSummary("fixture summary");
        invocations.update(persisted);
        var completed = invocations.listByParentThread("thread-1").get(0);
        assertEquals("completed", completed.getStatus());
        assertEquals("fixture summary", completed.getSummary());
        assertNotNull(completed.getCompletedAt());
        var json = com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(completed));
        assertFalse(json.containsKey("parentMessageId"));
        assertFalse(json.containsKey("inputJson"));
        assertFalse(com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(
                store.findThread("thread-1"))).containsKey("rootPlanId"));

        store.deleteThread("child-thread");
        assertNull(invocations.listByParentThread("thread-1").get(0).getChildThreadId());
        store.deleteThread("thread-1");
        assertTrue(invocations.listByParentThread("thread-1").isEmpty());
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
        terminal.discard(running, "failed", "network", "fixture connection failed", "fixture connection failed",
                0, "", List.of(Map.of("kind", "plan", "seq", 1)), plan, recovery);
        protocol.completeFromRuntime(turn.id(), "failed", "fixture connection failed", null);

        var history = messages.list("thread-1", 0, 20);
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
        var memory = messages.contextMessages("thread-1", 20);
        assertEquals(2, memory.size(), "the original user request must accompany a recovery-only assistant message");
        assertEquals("fixture", memory.get(0).content());
        assertEquals(recovery, memory.get(1).content());
        assertEquals(recovery, messages.contextMessage("thread-1", memory.get(1).sequence()).content());
        assertEquals(plan, JsonUtil.fromJsonString(messages.findLatestPlanJson("thread-1"), Map.class));

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
        for (var history : List.of(messages.list("thread-1", 0, 20), messages.listBefore("thread-1", 3, 20))) {
            var assistant = history.stream().filter(message -> "assistant".equals(message.get("role"))).findFirst().orElseThrow();
            assertEquals(reply, assistant.get("content"));
            assertFalse(assistant.containsKey("recoveryContext"));
            assertFalse(assistant.toString().contains(recovery));
        }
        assertEquals(recovery, messages.contextMessage("thread-1", 2).content());
        assertEquals(reply, jdbc.queryForObject("SELECT content FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        jdbc.update("UPDATE ai_messages SET recovery_context=NULL WHERE message_id=?", turn.assistantItemId());
        assertEquals(reply, messages.contextMessage("thread-1", 2).content());
        assertEquals(reply, messages.listBefore("thread-1", 3, 20).get(1).get("content"));
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

        assertEquals(1, leases.recoverOrphanedRuns("thread-1", 1000).size());
        assertEquals(nodes, jdbc.queryForObject("SELECT nodes_json FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        assertEquals("partial reply", jdbc.queryForObject("SELECT content FROM ai_messages WHERE message_id=?", String.class, turn.assistantItemId()));
        assertTrue(messages.contextMessage("thread-1", 2).content().contains("可用于继续任务"));
        assertFalse(messages.list("thread-1", 0, 20).toString().contains("可用于继续任务"));
    }

    @Test
    void orphanRecoveryRollsBackIfItsCompletionEventCannotBePersisted() {
        var turn = reserve();
        var running = beginRun(turn);
        jdbc.execute("CREATE TRIGGER reject_event BEFORE INSERT ON ai_events BEGIN SELECT RAISE(ABORT, 'event rejected'); END");

        assertThrows(RuntimeException.class, () -> leases.recoverOrphanedRuns("thread-1", 1000));

        assertEquals("running", jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id=?", String.class, running.runId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status='pending'", Integer.class));
        assertEquals("inProgress", turns.find(turn.id()).getProtocolStatus());
        assertTrue(journal.listAfter("thread-1", 0, 200).isEmpty());
    }

    @Test
    void restoresNewestEligiblePlanSnapshotWithoutChangingMemoryOrder() {
        var turn = reserve();
        for (int sequence = 3; sequence <= 6; sequence++) appendMessage(turn.id(), sequence);
        jdbc.update("UPDATE ai_messages SET status='committed' WHERE thread_id='thread-1'");
        jdbc.update("UPDATE ai_messages SET plan_json=? WHERE thread_id='thread-1' AND message_seq=2", "{\"step\":\"older\"}");
        String latest = "{\"step\":\"latest\"}";
        jdbc.update("UPDATE ai_messages SET plan_json=? WHERE message_id='message-3'", latest);
        jdbc.update("UPDATE ai_messages SET plan_json=? WHERE message_id='message-4'", "\t\u2003");
        jdbc.update("UPDATE ai_messages SET plan_json='null' WHERE message_id='message-5'");
        jdbc.update("UPDATE ai_messages SET status='pending', plan_json=? WHERE message_id='message-6'", "{\"step\":\"pending\"}");

        assertEquals(latest, messages.findLatestPlanJson("thread-1"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), messages.contextMessages("thread-1", 20).stream()
                .map(ConversationMessage::sequence).toList());

        for (int sequence = 7; sequence <= 54; sequence++) appendMessage(turn.id(), sequence);
        jdbc.update("UPDATE ai_messages SET status='committed' WHERE thread_id='thread-1' AND message_seq > 6");
        assertNull(messages.findLatestPlanJson("thread-1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalRunFailureRollsBackMessagesAndTurnState(boolean discarded) {
        var turn = reserve();
        var running = beginRun(turn);
        var beforeTurn = jdbc.queryForMap("SELECT * FROM ai_turns WHERE turn_id=?", turn.id());
        var beforeRun = jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id=?", running.runId());
        var beforeMessages = jdbc.queryForList("SELECT * FROM ai_messages WHERE turn_id=? ORDER BY message_seq", turn.id());
        jdbc.execute("CREATE TRIGGER reject_terminal_run BEFORE UPDATE OF status ON ai_runs "
                + "WHEN NEW.status <> 'running' BEGIN SELECT RAISE(ABORT, 'terminal rejected'); END");

        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            if (discarded) {
                terminal.discard(running, "failed", "network", "fixture error", "raw error", 1,
                        "partial", List.of(Map.of("kind", "text")), Map.of("step", 1), "private recovery");
            } else {
                terminal.complete(running, "answer", List.of(Map.of("kind", "text")),
                        Map.of("ok", true), Map.of("step", 1), 1);
            }
        });

        assertTrue(failure.getMessage().contains("terminal rejected"), failure::getMessage);
        assertEquals(beforeTurn, jdbc.queryForMap("SELECT * FROM ai_turns WHERE turn_id=?", turn.id()));
        assertEquals(beforeRun, jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id=?", running.runId()));
        assertEquals(beforeMessages,
                jdbc.queryForList("SELECT * FROM ai_messages WHERE turn_id=? ORDER BY message_seq", turn.id()));
    }

    private PersistedTurn beginRun(AiTurnProtocolService.TurnSnapshot turn) {
        return store.beginTurn(turn.id(), turn.userItemId(), turn.assistantItemId(), "thread-1", null,
                "fixture", "fixture", null, 1L, null, AiTurnTrace.start("fixture", "thread-1", 1L), null);
    }

    @Test
    void startupFailureIsTerminalReplayableAndIdempotentWithoutARun() {
        var turn = reserve();
        assertTrue(protocol.tryStart(turn.id()).claimed());

        assertEquals("failed", protocol.failStart(turn.id(), "session expired", null).status());
        protocol.failStart(turn.id(), "session expired", null);

        assertTrue(protocol.listDispatchableThreadIds().isEmpty());
        assertEquals("failed", store.findThread("thread-1").getRunStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE status = 'discarded'", Integer.class));
        List<AiSseEvent> events = journal.listAfter("thread-1", 0L, 200);
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

        assertEquals("inProgress", turns.find(turn.id()).getProtocolStatus());
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
        assertTrue(journal.listAfter("thread-1", 0L, 200).isEmpty());
    }

    @Test
    void historyCursorIsUnaffectedByNewMessagesAndRetainsProtocolMetadata() {
        var turn = reserve();
        for (int sequence = 3; sequence <= 205; sequence++) appendMessage(turn.id(), sequence);
        AiThreadQueryService query = new AiThreadQueryService(store, messages, journal, protocol);
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
        Map<String, Object> oldest = query.messages("thread-1", 0, 50, 3L);
        assertEquals(1L, oldest.get("nextBeforeSequence"));
        assertEquals(false, oldest.get("hasMore"));
        assertEquals(false, query.messages("thread-1", 0, 50, 1L).get("hasMore"));
        assertFalse(this.messages.hasBefore("missing-thread", 206L));
        assertEquals(206, jdbc.queryForObject("SELECT COUNT(*) FROM ai_messages WHERE thread_id='thread-1'", Integer.class));
    }

    private AiTurnProtocolService.TurnSnapshot reserve() {
        return protocol.begin("thread-1", "client-1", "platform", "{}", "fixture", null, null).turn();
    }

    private int concurrentSuccesses(Callable<Boolean> attempt) throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try {
            int accepted = 0;
            for (var result : workers.invokeAll(Collections.nCopies(4, attempt), 5, TimeUnit.SECONDS)) {
                if (result.get()) accepted++;
            }
            return accepted;
        } finally {
            workers.shutdownNow();
        }
    }

    private AiUserInputRequest confirmation(String requestId) {
        AiUserInputRequest question = new AiUserInputRequest();
        question.setRequestId(requestId);
        question.setThreadId("thread-1");
        question.setRequestType(AiUserInputRequest.TYPE_CONFIRMATION);
        question.setPrompt("Confirm fixture update?");
        question.setOptionsJson("[{\"label\":\"Confirm\",\"value\":\"confirm\",\"intent\":\"CONFIRM\"},"
                + "{\"label\":\"Cancel\",\"value\":\"cancel\",\"intent\":\"REJECT\"}]");
        question.setAllowFreeText(false);
        question.setToolName("updateProfile");
        question.setArgumentsHash("fixture-hash");
        question.setStatus(AiUserInputRequest.STATUS_PENDING);
        question.setCreatedAt(1L);
        question.setExpiresAt(Long.MAX_VALUE);
        return question;
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
