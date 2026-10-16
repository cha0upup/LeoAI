package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.thread.*;
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
        assertEquals(206, older.get("total"));
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
