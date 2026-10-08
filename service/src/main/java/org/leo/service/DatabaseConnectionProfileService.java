package org.leo.service;

import org.leo.core.entity.AgentDatabaseConnection;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.leo.service.DatabaseConnectionProfileException.Kind.FORBIDDEN;
import static org.leo.service.DatabaseConnectionProfileException.Kind.NOT_FOUND;
import static org.leo.service.DatabaseConnectionProfileException.Kind.PERSISTENCE;
import static org.leo.service.DatabaseConnectionProfileException.Kind.VALIDATION;

/**
 * Shared application service for HTTP and AI database-profile management.
 *
 * <p>Profiles are scoped to a Agent. Updates are patch-based: omitted
 * connection and metadata fields retain their persisted values.</p>
 */
@Service
public final class DatabaseConnectionProfileService {

    private final AgentDatabaseConnectionService connectionService;
    private final SqlDialectRegistry sqlDialectRegistry;

    public DatabaseConnectionProfileService(AgentDatabaseConnectionService connectionService,
                                            SqlDialectRegistry sqlDialectRegistry) {
        this.connectionService = connectionService;
        this.sqlDialectRegistry = sqlDialectRegistry;
    }

    public Map<String, Object> create(String userId,
                                      String agentId,
                                      Map<String, Object> params) {
        requireContext(userId, agentId);
        if (params == null) throw failure(VALIDATION, "params不能为空");
        if (text(params.get("connectionId")) != null) {
            throw failure(VALIDATION, "新增数据库连接时不要传 connectionId");
        }
        return persist(userId, agentId, null, params);
    }

    public Map<String, Object> update(String userId,
                                      String agentId,
                                      String connectionId,
                                      Map<String, Object> patch) {
        requireContext(userId, agentId);
        if (patch == null) throw failure(VALIDATION, "patch不能为空");
        AgentDatabaseConnection existing = requireAgentConnection(connectionId, agentId);
        return persist(userId, agentId, existing, patch);
    }

    /**
     * HTTP save contract: a missing connectionId creates a profile; otherwise
     * the supplied values patch the existing profile.
     */
    public Map<String, Object> save(String userId,
                                    String agentId,
                                    Map<String, Object> params) {
        requireContext(userId, agentId);
        if (params == null) throw failure(VALIDATION, "params不能为空");
        String connectionId = text(params.get("connectionId"));
        if (connectionId == null) return persist(userId, agentId, null, params);
        return persist(userId, agentId,
                requireAgentConnection(connectionId, agentId), params);
    }

    public List<Map<String, Object>> listByAgent(String userId, String agentId) {
        requireContext(userId, agentId);
        return connectionService.findByAgentId(agentId).stream()
                .map(connectionService::toConnectionView)
                .toList();
    }

    public void delete(String userId, String agentId, String connectionId) {
        requireContext(userId, agentId);
        AgentDatabaseConnection connection = requireAgentConnection(connectionId, agentId);
        if (!connectionService.deleteById(connection.getConnectionId(), agentId)) {
            throw failure(PERSISTENCE, "删除数据库连接失败");
        }
    }

    public Map<String, Object> setEnabled(String userId,
                                          String agentId,
                                          String connectionId,
                                          boolean enabled) {
        requireContext(userId, agentId);
        AgentDatabaseConnection connection = requireAgentConnection(connectionId, agentId);
        if (!connectionService.setEnabled(connection.getConnectionId(), agentId, enabled)) {
            throw failure(PERSISTENCE, "更新数据库连接状态失败");
        }
        return Map.of("connectionId", connection.getConnectionId(), "status", enabled ? 1 : 0);
    }

    public DatabaseConnectionSpec resolveActive(String userId,
                                                String agentId,
                                                String connectionId) {
        requireContext(userId, agentId);
        AgentDatabaseConnection connection = requireAgentConnection(connectionId, agentId);
        try {
            return connectionService.toActiveConnectionSpec(connection);
        } catch (IllegalArgumentException error) {
            throw failure(VALIDATION, error.getMessage());
        }
    }

    private Map<String, Object> persist(String userId,
                                        String agentId,
                                        AgentDatabaseConnection existing,
                                        Map<String, Object> params) {
        AgentDatabaseConnection connection = existing == null ? new AgentDatabaseConnection() : existing;
        if (existing == null) {
            connection.setCreateUserId(userId);
            connection.setAgentId(agentId);
        }
        DatabaseConnectionSpec spec = connectionSpec(params, existing);
        validateDialect(spec);
        connectionService.applyConnectionSpec(connection, spec);
        applyMetadata(connection, params, spec);

        if (!connectionService.saveOrUpdate(connection)) {
            throw failure(PERSISTENCE, "保存数据库连接失败");
        }
        return connectionService.toConnectionView(connection);
    }

    private DatabaseConnectionSpec connectionSpec(Map<String, Object> params,
                                                  AgentDatabaseConnection existing) {
        Object nested = params.get("connection");
        if (existing == null && !(nested instanceof Map<?, ?>)) {
            throw failure(VALIDATION, "connection 不能为空");
        }
        if (existing != null && nested == null) {
            return DatabaseConnectionSpec.fromMap(currentConnectionValues(existing));
        }
        if (!(nested instanceof Map<?, ?> source)) {
            throw failure(VALIDATION, "connection 必须是对象");
        }

        Map<String, Object> supplied = stringKeyMap(source);
        Map<String, Object> values = existing == null
                ? supplied : mergeMaps(currentConnectionValues(existing), supplied);
        try {
            String dialect = text(values.get("dialect"));
            if (dialect != null && sqlDialectRegistry.supports(dialect)) {
                values.put("dialect", sqlDialectRegistry.canonicalType(dialect));
            }
            return DatabaseConnectionSpec.fromMap(values);
        } catch (IllegalArgumentException error) {
            throw failure(VALIDATION, error.getMessage());
        }
    }

    private void validateDialect(DatabaseConnectionSpec spec) {
        if (sqlDialectRegistry.supports(spec.getDialect())) {
            return;
        }
        throw failure(VALIDATION, "不支持的数据库方言: " + spec.getDialect()
                + "。支持的方言: " + String.join(", ", sqlDialectRegistry.getSupportedTypes())
                + "。未内置的数据库请使用 dialect=generic、connectionMode=custom，"
                + "并配置 runtimeOptions.java(driverClass、jdbcUrl) 或 "
                + "runtimeOptions.php(pdoDriver、dsn)");
    }

    private void applyMetadata(AgentDatabaseConnection connection,
                               Map<String, Object> params,
                               DatabaseConnectionSpec spec) {
        String connectionName = params.containsKey("connectionName")
                ? text(params.get("connectionName"))
                : text(connection.getConnectionName());
        if (connectionName == null) connectionName = generateName(spec);
        if (connectionService.existsByName(
                connection.getAgentId(), connectionName, connection.getConnectionId())) {
            connectionName = connectionName + "_" + System.currentTimeMillis();
        }
        connection.setConnectionName(connectionName);

        Integer status = optionalInteger(params.get("status"), "status");
        if (status != null) {
            if (status != 0 && status != 1) throw failure(VALIDATION, "status必须是0或1");
            connection.setStatus(status);
        }
        Integer maxConnections = optionalInteger(params.get("maxConnections"), "maxConnections");
        if (maxConnections != null) {
            if (maxConnections < 1) throw failure(VALIDATION, "maxConnections必须大于0");
            connection.setMaxConnections(maxConnections);
        }
        Integer timeoutSeconds = optionalInteger(params.get("timeoutSeconds"), "timeoutSeconds");
        if (timeoutSeconds != null) {
            if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw failure(VALIDATION, "timeoutSeconds必须在1到300之间");
            }
            connection.setTimeoutSeconds(timeoutSeconds);
        }
        if (params.containsKey("description")) {
            connection.setDescription(nullableText(params.get("description")));
        }
        if (params.containsKey("remark")) {
            connection.setRemark(nullableText(params.get("remark")));
        }
    }

    private Map<String, Object> currentConnectionValues(AgentDatabaseConnection existing) {
        Map<String, Object> current = connectionService.toConnectionSpec(existing).toMap();
        current.put("password", "");
        return current;
    }

    private AgentDatabaseConnection requireAgentConnection(String connectionId,
                                                             String agentId) {
        String normalizedId = text(connectionId);
        if (normalizedId == null) throw failure(VALIDATION, "connectionId不能为空");
        AgentDatabaseConnection connection = connectionService.findById(normalizedId);
        if (connection == null) throw failure(NOT_FOUND, "数据库连接不存在");
        if (!Objects.equals(connection.getAgentId(), agentId)) {
            throw failure(FORBIDDEN, "数据库连接配置与当前 Agent 不匹配");
        }
        return connection;
    }

    private void requireContext(String userId, String agentId) {
        if (text(userId) == null) throw failure(VALIDATION, "用户上下文缺失");
        if (text(agentId) == null) throw failure(VALIDATION, "Agent ID 不能为空");
    }

    private String generateName(DatabaseConnectionSpec connection) {
        String locator = connection.getDatabase();
        if ("sqlite".equals(connection.getDialect())) locator = connection.getFile();
        else if ("oracle".equals(connection.getDialect())) {
            locator = "sid".equals(connection.getVariant())
                    ? connection.getSid() : connection.getService();
        }
        if (locator == null || locator.isBlank()) locator = connection.getHost();
        if (locator == null || locator.isBlank()) locator = "connection";
        int slash = Math.max(locator.lastIndexOf('/'), locator.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < locator.length()) locator = locator.substring(slash + 1);
        return (connection.getDialect() + "_" + locator)
                .replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private Integer optionalInteger(Object value, String name) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        String text = text(value);
        if (text == null) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException error) {
            throw failure(VALIDATION, name + "必须是整数");
        }
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base,
                                          Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(base);
        patch.forEach((key, value) -> {
            Object current = merged.get(key);
            if (current instanceof Map<?, ?> currentMap && value instanceof Map<?, ?> patchMap) {
                merged.put(key, mergeMaps(stringKeyMap(currentMap), stringKeyMap(patchMap)));
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private DatabaseConnectionProfileException failure(
            DatabaseConnectionProfileException.Kind kind, String message) {
        return new DatabaseConnectionProfileException(kind, message);
    }
}
