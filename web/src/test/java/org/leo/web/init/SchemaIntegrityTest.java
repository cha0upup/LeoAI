package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIntegrityTest {

    @Test
    void freshSchemaEnforcesDomainChecksAndAiCascadeRelationships() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO agents
                          (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link,
                           req_disguise_id, resp_disguise_id, create_time, update_time)
                        VALUES ('request-policy-default', 'test', 'root', 'user', '/',
                                'request', 'response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
                assertEquals(1, scalar(statement,
                        "SELECT max_req_count FROM agents WHERE agent_id='request-policy-default'"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO agents
                          (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link,
                           req_disguise_id, resp_disguise_id, max_req_count, create_time, update_time)
                        VALUES ('request-policy-invalid', 'test', 'root', 'user', '/',
                                'request', 'response', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """));
            }

            assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO users
                              (user_id, user_name, password, privilege, status, login_count, create_time, update_time)
                            VALUES ('invalid', 'invalid', 'hash', 'superuser', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """);
                }
            });

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO ai_threads
                          (thread_id, scope, title, created_at, last_active_at)
                        VALUES ('thread-1', 'platform', 'test', 1, 1)
                        """);
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO ai_turns
                          (turn_id, thread_id, status, created_at)
                        VALUES ('invalid-turn', 'thread-1', 'completed', 1)
                        """));
                statement.executeUpdate("""
                        INSERT INTO ai_turns
                          (turn_id, thread_id, status, created_at)
                        VALUES ('turn-1', 'thread-1', 'pending', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_thread_leases
                          (thread_id, owner_id, lease_token, acquired_at, heartbeat_at, expires_at)
                        VALUES ('thread-1', 'owner-1', 'lease-1', 1, 1, 10)
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_messages
                          (message_id, thread_id, turn_id, run_id, message_seq, status,
                           role, content, timestamp)
                        VALUES ('message-queued', 'thread-1', 'turn-1', NULL, 1, 'pending',
                                'user', 'queued command', 1)
                        """);
                assertEquals(0, statement.executeUpdate("""
                        INSERT INTO ai_thread_leases
                          (thread_id, owner_id, lease_token, acquired_at, heartbeat_at, expires_at)
                        VALUES ('thread-1', 'owner-2', 'lease-2', 2, 2, 12)
                        ON CONFLICT(thread_id) DO UPDATE SET
                          owner_id=excluded.owner_id, lease_token=excluded.lease_token,
                          acquired_at=excluded.acquired_at, heartbeat_at=excluded.heartbeat_at,
                          expires_at=excluded.expires_at
                        WHERE ai_thread_leases.expires_at <= excluded.acquired_at
                        """));
                assertEquals(0, statement.executeUpdate("""
                        UPDATE ai_thread_leases SET heartbeat_at=11, expires_at=20
                        WHERE thread_id='thread-1' AND owner_id='owner-1'
                          AND lease_token='lease-1' AND expires_at > 11
                        """));
                assertEquals(1, statement.executeUpdate("""
                        UPDATE ai_thread_leases SET heartbeat_at=2, expires_at=20
                        WHERE thread_id='thread-1' AND owner_id='owner-1'
                          AND lease_token='lease-1' AND expires_at > 2
                        """));
                statement.executeUpdate("""
                        INSERT INTO ai_runs
                          (run_id, thread_id, turn_id, status, started_at, trace_id)
                        VALUES ('run-1', 'thread-1', 'turn-1', 'running', 1, 'trace-1')
                        """);
                assertEquals(1, statement.executeUpdate("""
                        UPDATE ai_messages SET run_id='run-1'
                        WHERE message_id='message-queued' AND run_id IS NULL
                        """));
                statement.executeUpdate("""
                        INSERT INTO ai_messages
                          (message_id, thread_id, turn_id, run_id, message_seq, status,
                           role, content, timestamp)
                        VALUES ('message-1', 'thread-1', 'turn-1', 'run-1', 2, 'pending',
                                'user', 'hello', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_events
                          (event_id, run_id, thread_id, turn_id, item_id, event_seq,
                           timestamp, name, data_json)
                        VALUES ('event-1', 'run-1', 'thread-1', 'turn-1',
                                'message-1', 1, 1, 'delta', '"hello"')
                        """);
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO ai_events
                          (event_id, thread_id, event_seq, timestamp, name)
                        VALUES ('event-duplicate', 'thread-1', 1, 2, 'delta')
                        """));
                assertEquals(0, statement.executeUpdate("""
                        UPDATE ai_runs SET status='completed', finished_at=2
                        WHERE run_id='run-1' AND EXISTS (
                          SELECT 1 FROM ai_thread_leases l
                          WHERE l.thread_id=ai_runs.thread_id
                            AND l.lease_token='wrong-token' AND l.expires_at > 2
                        )
                        """));
                assertEquals(0, statement.executeUpdate("""
                        UPDATE ai_runs SET status='completed', finished_at=21
                        WHERE run_id='run-1' AND status='running'
                          AND lease_token='lease-1' AND EXISTS (
                          SELECT 1 FROM ai_thread_leases l
                          WHERE l.thread_id=ai_runs.thread_id
                            AND l.lease_token='lease-1' AND l.expires_at > 21
                        )
                        """));
                assertEquals(1, statement.executeUpdate("""
                        UPDATE ai_runs SET status='completed', finished_at=3
                        WHERE run_id='run-1' AND status='running' AND EXISTS (
                          SELECT 1 FROM ai_thread_leases l
                          WHERE l.thread_id=ai_runs.thread_id
                            AND l.lease_token='lease-1' AND l.expires_at > 3
                        )
                        """));
                statement.executeUpdate("DELETE FROM ai_threads WHERE thread_id = 'thread-1'");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM ai_messages")) {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }
            try (Statement statement = connection.createStatement()) {
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM ai_turns"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM ai_runs"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM ai_events"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM ai_thread_leases"));
            }
        }
    }

    private int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
