package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable result plane for network discovery tasks. */
@Service
public final class NetworkProbeResultStore {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_FILTER_VALUES = 256;
    private static final int MAX_SUMMARY_HOSTS = 160;
    private static final Logger logger = LoggerFactory.getLogger(NetworkProbeResultStore.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public NetworkProbeResultStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedTasks() {
        ensureTaskErrorColumn();
        ensureFingerprintColumn();
        ensureResponseSizeColumn();
        ensureStageColumn();
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE scan_tasks SET status='FAILED', outcome='FAILED', "
                             + "current_stage=NULL, progress=0, error_message=?, updated_at=?, finished_at=? "
                             + "WHERE status IN ('RUNNING', 'PAUSED')")) {
            statement.setString(1, "服务端重启时任务被中断");
            statement.setString(2, now);
            statement.setString(3, now);
            int count = statement.executeUpdate();
            if (count > 0) logger.warn("Marked {} interrupted network scan tasks as FAILED", count);
        } catch (SQLException error) {
            logger.warn("Unable to reconcile interrupted network scan tasks", error);
        }
    }

    public boolean createTask(String taskId, String sessionId, String name, Map<String, Object> config) {
        if (taskId == null || taskId.isBlank() || sessionId == null || sessionId.isBlank()) return false;
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT OR IGNORE INTO scan_tasks "
                             + "(task_id, session_id, name, status, outcome, progress, target_count, "
                             + "open_count, service_count, error_count, config_json, created_at, updated_at) "
                             + "VALUES (?, ?, ?, 'RUNNING', 'RUNNING', 0, ?, 0, 0, 0, ?, ?, ?)")) {
            statement.setString(1, taskId);
            statement.setString(2, sessionId);
            statement.setString(3, name);
            statement.setInt(4, targetCount(config));
            statement.setString(5, json(config));
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
            return true;
        } catch (SQLException error) {
            logger.warn("Unable to create network scan result task {}", taskId, error);
            return false;
        }
    }

    public boolean append(String taskId, List<Map<String, Object>> observations,
                          List<Map<String, Object>> errors) {
        if (taskId == null || taskId.isBlank()) return false;
        List<Map<String, Object>> safeObservations = observations == null ? List.of() : observations;
        List<Map<String, Object>> safeErrors = errors == null ? List.of() : errors;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement observationStatement = connection.prepareStatement(
                         "INSERT OR IGNORE INTO scan_observations "
                                 + "(task_id, dedupe_key, stage, host, port, protocol, state, observation_json, created_at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement endpointStatement = connection.prepareStatement(endpointSql());
                 PreparedStatement evidenceStatement = connection.prepareStatement(
                         "INSERT OR IGNORE INTO scan_evidence "
                                 + "(evidence_id, task_id, endpoint_id, content_json, content_bytes, sha256, created_at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (Map<String, Object> observation : safeObservations) {
                    persistObservation(observationStatement, endpointStatement,
                            evidenceStatement, taskId, observation);
                }
                java.util.Set<String> observationErrorKeys = new java.util.HashSet<>();
                for (Map<String, Object> observation : safeObservations) {
                    if ("error".equalsIgnoreCase(text(observation.get("state")))) {
                        observationErrorKeys.add(errorKey(observation));
                    }
                }
                java.util.Set<String> persistedErrorKeys = new java.util.HashSet<>();
                for (Map<String, Object> error : safeErrors) {
                    Map<String, Object> normalized = new LinkedHashMap<>(error == null ? Map.of() : error);
                    normalized.putIfAbsent("stage", "error");
                    normalized.putIfAbsent("state", "error");
                    String key = errorKey(normalized);
                    if (!persistedErrorKeys.add(key) || observationErrorKeys.contains(key)) continue;
                    persistObservation(observationStatement, endpointStatement,
                            evidenceStatement, taskId, normalized);
                }
                observationStatement.executeBatch();
                endpointStatement.executeBatch();
                evidenceStatement.executeBatch();
                connection.commit();
                return true;
            } catch (Exception error) {
                connection.rollback();
                logger.warn("Unable to append network scan results for task {}", taskId, error);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception error) {
            // A temporary database failure is reported through task metrics later.
            logger.warn("Unable to persist network scan results for task {}", taskId, error);
        }
        return false;
    }

    public boolean updateFingerprints(String taskId, List<Map<String, Object>> endpoints) {
        if (taskId == null || taskId.isBlank()) return false;
        List<Map<String, Object>> safeEndpoints = endpoints == null ? List.of() : endpoints;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE scan_endpoint_results SET fingerprint_json=?, updated_at=? "
                            + "WHERE task_id=? AND endpoint_id=?")) {
                String now = Instant.now().toString();
                for (Map<String, Object> endpoint : safeEndpoints) {
                    Map<String, Object> fingerprint = map(endpoint.get("fingerprint"));
                    if (fingerprint.isEmpty()) continue;
                    statement.setString(1, json(fingerprint));
                    statement.setString(2, now);
                    statement.setString(3, taskId);
                    statement.setString(4, endpointId(endpoint));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                return true;
            } catch (Exception error) {
                connection.rollback();
                logger.warn("Unable to persist network scan fingerprints for task {}", taskId, error);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception error) {
            logger.warn("Unable to persist network scan fingerprints for task {}", taskId, error);
        }
        return false;
    }

    public void updateTask(String taskId, String status, String outcome, String currentStage,
                           int progress, int targetCount) {
        updateTask(taskId, status, outcome, currentStage, progress, targetCount, null, null);
    }

    public void updateTask(String taskId, String status, String outcome, String currentStage,
                           int progress, int targetCount, String errorMessage) {
        updateTask(taskId, status, outcome, currentStage, progress, targetCount, errorMessage, null);
    }

    public void updateTask(String taskId, String status, String outcome, String currentStage,
                           int progress, int targetCount, String errorMessage,
                           List<Map<String, Object>> stages) {
        if (taskId == null || taskId.isBlank()) return;
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE scan_tasks SET status=?, outcome=?, current_stage=?, progress=?, "
                             + "error_message=COALESCE(?, error_message), "
                             + "stage_json=COALESCE(?, stage_json), "
                             + "target_count=CASE WHEN ? > 0 THEN ? ELSE target_count END, "
                             + "open_count=(SELECT COUNT(*) FROM scan_endpoint_results WHERE task_id=? AND state='open'), "
                             + "service_count=(SELECT COUNT(*) FROM scan_endpoint_results "
                             + "WHERE task_id=? AND service IS NOT NULL AND service <> '' AND service <> 'unknown'), "
                             + "error_count=(SELECT COUNT(*) FROM scan_observations WHERE task_id=? AND state='error'), "
                             + "updated_at=?, finished_at=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELLED') THEN ? ELSE finished_at END "
                             + "WHERE task_id=?")) {
            statement.setString(1, value(status, "RUNNING"));
            statement.setString(2, value(outcome, "RUNNING"));
            statement.setString(3, currentStage);
            statement.setInt(4, Math.max(0, Math.min(100, progress)));
            statement.setString(5, errorMessage == null || errorMessage.isBlank() ? null : errorMessage);
            statement.setString(6, stages == null || stages.isEmpty() ? null : json(stages));
            statement.setInt(7, targetCount);
            statement.setInt(8, targetCount);
            statement.setString(9, taskId);
            statement.setString(10, taskId);
            statement.setString(11, taskId);
            statement.setString(12, now);
            statement.setString(13, value(outcome, "RUNNING"));
            statement.setString(14, now);
            statement.setString(15, taskId);
            statement.executeUpdate();
        } catch (SQLException error) {
            logger.warn("Unable to update network scan task {}", taskId, error);
        }
    }

    public Map<String, Object> summary(String sessionId, String taskId) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                     "SELECT task_id, name, status, outcome, current_stage, progress, target_count, "
                             + "open_count, service_count, error_count, error_message, stage_json, "
                             + "created_at, updated_at, finished_at "
                             + "FROM scan_tasks WHERE task_id=? AND session_id=?")) {
            statement.setString(1, taskId);
            statement.setString(2, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Map.of();
                Map<String, Object> task = taskMap(result);
                task.putAll(reachableHostSummary(connection, taskId));
                return task;
            }
        } catch (SQLException error) {
            logger.warn("Unable to read network scan task {}", taskId, error);
            return Map.of();
        }
    }

    public List<Map<String, Object>> list(String sessionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT task_id, name, status, outcome, current_stage, progress, target_count, "
                             + "open_count, service_count, error_count, error_message, stage_json, "
                             + "created_at, updated_at, finished_at "
                             + "FROM scan_tasks WHERE session_id=? ORDER BY created_at DESC")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> task = taskMap(rows);
                    task.putAll(reachableHostSummary(connection, text(task.get("taskId"))));
                    result.add(task);
                }
            }
        } catch (SQLException error) {
            logger.warn("Unable to list network scan tasks for session {}", sessionId, error);
        }
        return result;
    }

    public Map<String, Object> queryResults(String sessionId, String taskId, Map<String, Object> request) {
        if (!ownsTask(sessionId, taskId)) return Map.of();
        int page = boundedInt(request == null ? null : request.get("page"), 1, 1, Integer.MAX_VALUE);
        int pageSize = boundedInt(request == null ? null : request.get("pageSize"), 50, 1, MAX_PAGE_SIZE);
        Map<String, Object> filter = map(request == null ? null : request.get("filter"));
        QueryParts query = queryParts(taskId, filter);
        try (Connection connection = dataSource.getConnection()) {
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM scan_endpoint_results " + query.where)) {
                bind(count, query.parameters);
                try (ResultSet rows = count.executeQuery()) {
                    total = rows.next() ? rows.getLong(1) : 0L;
                }
            }
            List<Map<String, Object>> endpoints = new ArrayList<>();
            String sortField = value(request == null ? null : map(request.get("sort")).get("field"), "discoveredAt");
            String sortColumn = switch (sortField) {
                case "host" -> "host"; case "port" -> "port"; case "state" -> "state";
                case "service" -> "service"; case "statusCode" -> "status_code";
                case "responseSize" -> "response_size"; case "responseTime" -> "response_time";
                default -> "discovered_at";
            };
            String sortOrder = "asc".equalsIgnoreCase(value(request == null ? null : map(request.get("sort")).get("order"), "desc"))
                    ? "ASC" : "DESC";
            String sql = "SELECT endpoint_id, host, port, protocol, state, service, banner, title, "
                    + "status_code, response_size, server, location, content_type, fingerprint_json, confidence, response_time, evidence_id, discovered_at "
                    + "FROM scan_endpoint_results " + query.where
                    + " ORDER BY " + sortColumn + " " + sortOrder + ", result_id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, query.parameters);
                statement.setInt(query.parameters.size() + 1, pageSize);
                statement.setLong(query.parameters.size() + 2, (long) (page - 1) * pageSize);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) endpoints.add(endpointMap(rows));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("hasMore", (long) page * pageSize < total);
            result.put("endpoints", endpoints);
            return result;
        } catch (SQLException error) {
            logger.warn("Unable to query network scan results for task {}", taskId, error);
            return Map.of("taskId", taskId, "total", 0, "page", page, "pageSize", pageSize,
                    "hasMore", false, "endpoints", List.of());
        }
    }

    public Map<String, Object> summaryCounts(String sessionId, String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT open_count, service_count, error_count "
                             + "FROM scan_tasks WHERE task_id=? AND session_id=?")) {
            statement.setString(1, taskId);
            statement.setString(2, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Map.of();
                return Map.of("openCount", result.getInt(1),
                        "serviceCount", result.getInt(2), "errorCount", result.getInt(3));
            }
        } catch (SQLException error) {
            logger.warn("Unable to read network scan task counts {}", taskId, error);
            return Map.of();
        }
    }

    /** Removes a task and all of its endpoint, observation and evidence rows. */
    public boolean deleteTask(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank() || taskId == null || taskId.isBlank()) return false;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM scan_tasks WHERE task_id=? AND session_id=?")) {
            statement.setString(1, taskId.trim());
            statement.setString(2, sessionId.trim());
            return statement.executeUpdate() > 0;
        } catch (SQLException error) {
            logger.warn("Unable to delete network scan task {}", taskId, error);
            return false;
        }
    }

    /** Deletes every persisted network scan owned by a destroyed session. */
    public int deleteTasksBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM scan_tasks WHERE session_id=?")) {
            statement.setString(1, sessionId.trim());
            return statement.executeUpdate();
        } catch (SQLException error) {
            logger.warn("Unable to delete network scan tasks for session {}", sessionId, error);
            return 0;
        }
    }

    private void persistObservation(PreparedStatement observationStatement,
                                    PreparedStatement endpointStatement, PreparedStatement evidenceStatement,
                                    String taskId, Map<String, Object> observation) throws Exception {
        String host = text(observation.get("host"));
        int port = integer(observation.get("port"), 0);
        String protocol = value(observation.get("protocol"), "tcp");
        String stage = text(observation.get("stage"));
        String state = text(observation.get("state"));
        String encoded = json(observation);
        String dedupeKey = digest(taskId + "|" + stage + "|" + host + "|" + port + "|" + encoded);
        String now = Instant.now().toString();
        observationStatement.setString(1, taskId);
        observationStatement.setString(2, dedupeKey);
        observationStatement.setString(3, stage);
        observationStatement.setString(4, host);
        if (port > 0) observationStatement.setInt(5, port); else observationStatement.setObject(5, null);
        observationStatement.setString(6, protocol);
        observationStatement.setString(7, state);
        observationStatement.setString(8, encoded);
        observationStatement.setString(9, now);
        observationStatement.addBatch();

        if (host.isEmpty() || port < 1) return;
        if ("REACHABILITY".equalsIgnoreCase(text(observation.get("workflowStage")))) return;
        String endpointId = endpointId(host, port);
        Map<String, Object> evidence = map(observation.get("evidence"));
        String evidenceId = null;
        if (!evidence.isEmpty()) {
            String evidenceJson = json(evidence);
            evidenceId = digest(taskId + "|" + endpointId + "|" + evidenceJson);
            evidenceStatement.setString(1, evidenceId);
            evidenceStatement.setString(2, taskId);
            evidenceStatement.setString(3, endpointId);
            evidenceStatement.setString(4, evidenceJson);
            evidenceStatement.setInt(5, evidenceJson.getBytes(StandardCharsets.UTF_8).length);
            evidenceStatement.setString(6, digest(evidenceJson));
            evidenceStatement.setString(7, now);
            evidenceStatement.addBatch();
        }

        String workflowStage = text(observation.get("workflowStage"));
        String service = "SERVICE_PROBE".equalsIgnoreCase(workflowStage)
                ? service(observation, evidence) : null;
        endpointStatement.setString(1, taskId);
        endpointStatement.setString(2, endpointId);
        endpointStatement.setString(3, host);
        endpointStatement.setInt(4, port);
        endpointStatement.setString(5, protocol);
        endpointStatement.setString(6, state);
        endpointStatement.setString(7, service);
        endpointStatement.setString(8, text(evidence.get("banner")));
        endpointStatement.setString(9, text(evidence.get("title")));
        endpointStatement.setObject(10, number(evidence.get("statusCode")));
        Object responseSize = evidence.get("responseSize");
        if (responseSize == null) responseSize = evidence.get("bodyLength");
        endpointStatement.setObject(11, number(responseSize));
        endpointStatement.setString(12, text(evidence.get("server")));
        endpointStatement.setString(13, text(evidence.get("location")));
        endpointStatement.setString(14, text(evidence.get("contentType")));
        endpointStatement.setString(15, jsonOrNull(evidence.get("fingerprint")));
        endpointStatement.setObject(16, decimal(observation.get("confidence")));
        endpointStatement.setObject(17, number(observation.get("latencyMs")));
        endpointStatement.setString(18, evidenceId);
        endpointStatement.setString(19, now);
        endpointStatement.setString(20, now);
        endpointStatement.addBatch();
    }

    private String endpointSql() {
        return "INSERT INTO scan_endpoint_results "
                + "(task_id, endpoint_id, host, port, protocol, state, service, banner, title, status_code, response_size, "
                + "server, location, content_type, fingerprint_json, confidence, response_time, evidence_id, discovered_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(task_id, endpoint_id) DO UPDATE SET state=CASE "
                + "WHEN excluded.state='open' THEN 'open' "
                + "WHEN scan_endpoint_results.state='open' THEN scan_endpoint_results.state "
                + "ELSE excluded.state END, "
                + "service=COALESCE(excluded.service, scan_endpoint_results.service), "
                + "banner=COALESCE(excluded.banner, scan_endpoint_results.banner), "
                + "title=CASE WHEN excluded.title IS NOT NULL AND excluded.title <> '' "
                + "THEN excluded.title ELSE scan_endpoint_results.title END, "
                + "status_code=COALESCE(excluded.status_code, scan_endpoint_results.status_code), "
                + "response_size=COALESCE(excluded.response_size, scan_endpoint_results.response_size), "
                + "server=COALESCE(excluded.server, scan_endpoint_results.server), "
                + "location=COALESCE(excluded.location, scan_endpoint_results.location), "
                + "content_type=COALESCE(excluded.content_type, scan_endpoint_results.content_type), "
                + "fingerprint_json=COALESCE(excluded.fingerprint_json, scan_endpoint_results.fingerprint_json), "
                + "confidence=COALESCE(excluded.confidence, scan_endpoint_results.confidence), "
                + "response_time=COALESCE(excluded.response_time, scan_endpoint_results.response_time), "
                + "evidence_id=COALESCE(excluded.evidence_id, scan_endpoint_results.evidence_id), "
                + "updated_at=excluded.updated_at";
    }

    private boolean ownsTask(String sessionId, String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM scan_tasks WHERE task_id=? AND session_id=?")) {
            statement.setString(1, taskId);
            statement.setString(2, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException error) {
            return false;
        }
    }

    private QueryParts queryParts(String taskId, Map<String, Object> filter) {
        // The result plane is intentionally limited to live endpoints. Failed,
        // closed and filtered probes remain available in observations for task
        // accounting, but are not part of the user-facing asset result set.
        StringBuilder where = new StringBuilder("WHERE task_id=? AND LOWER(state)='open'");
        List<Object> parameters = new ArrayList<>();
        parameters.add(taskId);
        appendIn(where, parameters, "host", filter.get("hosts"));
        appendIn(where, parameters, "port", filter.get("ports"));
        appendIn(where, parameters, "service", filter.get("services"));
        if (filter.get("portMin") != null) {
            where.append(" AND port >= ?"); parameters.add(filter.get("portMin"));
        }
        if (filter.get("portMax") != null) {
            where.append(" AND port <= ?"); parameters.add(filter.get("portMax"));
        }
        if (filter.get("confidenceMin") != null) {
            Object confidence = filter.get("confidenceMin");
            if (confidence instanceof Number number && number.doubleValue() > 1.0) {
                confidence = number.doubleValue() / 100.0;
            }
            where.append(" AND COALESCE(confidence, 0) >= ?"); parameters.add(confidence);
        }
        if (Boolean.TRUE.equals(filter.get("hasService"))) where.append(" AND service IS NOT NULL AND service <> ''");
        if (Boolean.TRUE.equals(filter.get("hasTitle"))) where.append(" AND title IS NOT NULL AND title <> ''");
        if (filter.get("responseTimeMin") != null) {
            where.append(" AND COALESCE(response_time, 0) >= ?"); parameters.add(filter.get("responseTimeMin"));
        }
        if (filter.get("responseTimeMax") != null) {
            where.append(" AND COALESCE(response_time, 0) <= ?"); parameters.add(filter.get("responseTimeMax"));
        }
        String search = text(filter.get("searchText"));
        if (!search.isEmpty()) {
            where.append(" AND (LOWER(host) LIKE LOWER(?) OR LOWER(service) LIKE LOWER(?) "
                    + "OR LOWER(COALESCE(banner,'')) LIKE LOWER(?) OR LOWER(COALESCE(title,'')) LIKE LOWER(?))");
            String pattern = "%" + search + "%";
            parameters.add(pattern); parameters.add(pattern); parameters.add(pattern); parameters.add(pattern);
        }
        return new QueryParts(where.toString(), parameters);
    }

    private void appendIn(StringBuilder where, List<Object> parameters, String column, Object value) {
        if (!(value instanceof Collection<?> values) || values.isEmpty()) return;
        if (values.size() > MAX_FILTER_VALUES) {
            throw new IllegalArgumentException(column + "筛选值不能超过" + MAX_FILTER_VALUES + "个");
        }
        where.append(" AND ").append(column).append(" IN (");
        boolean first = true;
        for (Object item : values) {
            if (!first) where.append(',');
            where.append('?'); first = false; parameters.add(item);
        }
        where.append(')');
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
    }

    private Map<String, Object> taskMap(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", row.getString("task_id"));
        result.put("name", row.getString("name"));
        result.put("status", row.getString("status"));
        result.put("outcome", row.getString("outcome"));
        result.put("currentStage", row.getString("current_stage"));
        result.put("progress", row.getInt("progress"));
        result.put("targetCount", row.getInt("target_count"));
        result.put("openCount", row.getInt("open_count"));
        result.put("serviceCount", row.getInt("service_count"));
        result.put("errorCount", row.getInt("error_count"));
        result.put("error", row.getString("error_message"));
        Object stages = parseJson(row.getString("stage_json"));
        if (stages instanceof List<?> list && !list.isEmpty()) {
            result.put("stages", list);
            result.put("stageCount", list.size());
        }
        result.put("createdAt", row.getString("created_at"));
        result.put("updatedAt", row.getString("updated_at"));
        result.put("finishedAt", row.getString("finished_at"));
        return result;
    }

    private Map<String, Object> endpointMap(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpointId", row.getString("endpoint_id"));
        result.put("host", row.getString("host"));
        result.put("port", row.getInt("port"));
        result.put("protocol", row.getString("protocol"));
        result.put("state", row.getString("state"));
        result.put("service", row.getString("service"));
        result.put("banner", row.getString("banner"));
        result.put("title", row.getString("title"));
        result.put("statusCode", row.getObject("status_code"));
        result.put("responseSize", row.getObject("response_size"));
        result.put("server", row.getString("server"));
        result.put("location", row.getString("location"));
        result.put("contentType", row.getString("content_type"));
        result.put("fingerprint", parseJson(row.getString("fingerprint_json")));
        result.put("confidence", row.getObject("confidence"));
        result.put("responseTime", row.getObject("response_time"));
        result.put("evidenceId", row.getString("evidence_id"));
        result.put("discoveredAt", row.getString("discovered_at"));
        return result;
    }

    private int targetCount(Map<String, Object> config) {
        int explicit = integer(config == null ? null : config.get("targetCount"), 0);
        if (explicit > 0) return explicit;
        Object hosts = config == null ? null : config.get("hosts");
        if (hosts instanceof Collection<?> collection) return collection.size();
        Object targets = config == null ? null : config.get("targets");
        return targets instanceof Collection<?> collection ? collection.size() : 0;
    }

    private Map<String, Object> reachableHostSummary(Connection connection, String taskId) throws SQLException {
        // Any successful probe proves reachability, including port scans with discovery disabled.
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT host) FROM scan_observations "
                        + "WHERE task_id=? AND state='open' AND host IS NOT NULL AND host<>''")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) count = result.getInt(1);
            }
        }
        List<String> hosts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT host FROM scan_observations "
                        + "WHERE task_id=? AND state='open' AND host IS NOT NULL AND host<>'' "
                        + "GROUP BY host ORDER BY MIN(observation_id) LIMIT ?")) {
            statement.setString(1, taskId);
            statement.setInt(2, MAX_SUMMARY_HOSTS);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String host = result.getString(1);
                    if (host != null && !host.isBlank()) hosts.add(host);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reachableHostCount", Integer.valueOf(count));
        summary.put("reachableHostList", hosts);
        return summary;
    }

    private void ensureFingerprintColumn() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE scan_endpoint_results ADD COLUMN fingerprint_json TEXT");
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                logger.warn("Unable to ensure network fingerprint result column", error);
            }
        }
    }

    private void ensureResponseSizeColumn() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE scan_endpoint_results ADD COLUMN response_size INTEGER");
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                logger.warn("Unable to ensure network response size result column", error);
            }
        }
    }

    private void ensureTaskErrorColumn() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE scan_tasks ADD COLUMN error_message TEXT");
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                logger.warn("Unable to ensure network task error column", error);
            }
        }
    }

    private void ensureStageColumn() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE scan_tasks ADD COLUMN stage_json TEXT");
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                logger.warn("Unable to ensure network task stage column", error);
            }
        }
    }

    private String endpointId(Map<String, Object> endpoint) {
        return endpointId(text(endpoint.get("host")), integer(endpoint.get("port"), 0));
    }

    private String endpointId(String host, int port) {
        return "tcp|" + host + "|" + port;
    }

    private String errorKey(Map<String, Object> value) {
        String host = text(value.get("host"));
        String target = text(value.get("target"));
        String endpoint = host.isEmpty() ? target : host + ":" + integer(value.get("port"), 0);
        return text(value.get("workflowStage")) + "\u0000"
                + text(value.get("stage")) + "\u0000"
                + endpoint + "\u0000"
                + text(value.get("errorCode")) + "\u0000"
                + text(value.get("error"));
    }

    private String jsonOrNull(Object value) {
        Map<String, Object> object = map(value);
        return object.isEmpty() ? null : json(object);
    }

    private String service(Map<String, Object> observation, Map<String, Object> evidence) {
        String explicit = text(observation.get("service"));
        if (!explicit.isEmpty()) return explicit;
        if (evidence.get("statusCode") != null) {
            String protocol = text(observation.get("protocol"));
            return "https".equalsIgnoreCase(protocol) ? "https" : "http";
        }
        String banner = text(evidence.get("banner")).toLowerCase(java.util.Locale.ROOT);
        if (banner.startsWith("ssh-") || banner.contains("openssh")) return "ssh";
        if (banner.contains("mysql") || banner.contains("mariadb")) return "mysql";
        if (banner.contains("redis")) return "redis";
        if (banner.contains("mongodb")) return "mongodb";
        if (banner.contains("postgres")) return "postgresql";
        if (banner.contains("smtp")) return "smtp";
        if (banner.contains("ftp")) return "ftp";
        if (banner.contains("http/1.") || banner.contains("server:")) return "http";
        return null;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ignored) { return "{}"; }
    }

    private Object parseJson(String value) {
        try { return objectMapper.readValue(value == null ? "{}" : value, Object.class); }
        catch (Exception ignored) { return Map.of(); }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String value(Object value, String fallback) {
        String text = text(value);
        return text.isEmpty() ? fallback : text;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? null : Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, integer(value, fallback)));
    }

    private record QueryParts(String where, List<Object> parameters) { }
}
