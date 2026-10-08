package org.leo.service;

import org.leo.core.entity.AgentDatabaseConnection;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.core.util.json.PortableJsonCodec;
import org.leo.dao.mapper.AgentDatabaseConnectionMapper;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentDatabaseConnectionService {

    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(^|[?&;])((?:password|passwd|pwd|token|access_token|secret|api_key)=)([^&;]*)");
    private static final Pattern AUTHORITY_PASSWORD = Pattern.compile("(://[^:/?#]+:)[^@/?#]+(@)");

    private final AgentDatabaseConnectionMapper mapper;
    private final DatabaseCredentialCryptoService credentialCrypto;

    public AgentDatabaseConnectionService(AgentDatabaseConnectionMapper mapper,
                                           DatabaseCredentialCryptoService credentialCrypto) {
        this.mapper = mapper;
        this.credentialCrypto = credentialCrypto;
    }

    public AgentDatabaseConnection findById(String connectionId) {
        return mapper.selectById(connectionId);
    }

    public List<AgentDatabaseConnection> findByAgentId(String agentId) {
        return mapper.selectByAgentId(agentId);
    }

    public boolean saveOrUpdate(AgentDatabaseConnection connection) {
        String callerPassword = connection.getPassword();
        String persistedPassword = credentialCrypto.isEncrypted(callerPassword)
                ? callerPassword
                : credentialCrypto.encrypt(callerPassword);
        connection.setPassword(persistedPassword);
        try {
            if (connection.getConnectionId() == null || connection.getConnectionId().isBlank()) {
                connection.setConnectionId(UUID.randomUUID().toString());
                connection.setCreateTime(new Date());
                connection.setUpdateTime(new Date());
                connection.setTestStatus(0);
                return mapper.insert(connection) > 0;
            }
            connection.setUpdateTime(new Date());
            return mapper.update(connection) > 0;
        } finally {
            connection.setPassword(callerPassword);
        }
    }

    public boolean deleteById(String connectionId, String agentId) {
        return mapper.deleteByIdAndAgent(connectionId, agentId) > 0;
    }

    public boolean existsByName(String agentId, String connectionName, String excludeConnectionId) {
        return mapper.existsByName(agentId, connectionName, excludeConnectionId);
    }

    public boolean recordTestResult(String connectionId, boolean success, String message) {
        if (connectionId == null || connectionId.isBlank()) return false;
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedMessage.length() > 1000) normalizedMessage = normalizedMessage.substring(0, 1000);
        return mapper.updateTestStatus(connectionId, success ? 1 : 2, normalizedMessage) > 0;
    }

    public boolean setEnabled(String connectionId, String agentId, boolean enabled) {
        if (connectionId == null || connectionId.isBlank()
                || agentId == null || agentId.isBlank()) return false;
        return mapper.updateStatusByAgent(connectionId, agentId, enabled ? 1 : 0) > 0;
    }

    public void applyConnectionSpec(AgentDatabaseConnection target, DatabaseConnectionSpec spec) {
        if (target == null) throw new IllegalArgumentException("数据库连接配置不能为空");
        if (spec == null) throw new IllegalArgumentException("connection 不能为空");
        String existingPassword = target.getPassword();
        target.setDialect(spec.getDialect());
        target.setUsername(spec.getUsername());
        if (target.getConnectionId() != null && !target.getConnectionId().isBlank()
                && (spec.getPassword() == null || spec.getPassword().isBlank())) {
            target.setPassword(existingPassword);
        } else {
            target.setPassword(spec.getPassword());
        }
        target.setTimeoutSeconds(spec.getTimeoutSeconds() == null ? 30 : spec.getTimeoutSeconds());
        Map<String, Object> persisted = new LinkedHashMap<>(spec.toMap());
        persisted.remove("password");
        target.setConnectionSpec(new String(PortableJsonCodec.encode(persisted), StandardCharsets.UTF_8));
        target.setTestStatus(0);
        target.setLastTestTime(null);
        target.setLastTestMessage(null);
    }

    public DatabaseConnectionSpec toConnectionSpec(AgentDatabaseConnection source) {
        Map<String, Object> values = storedConnectionValues(source);
        values.put("password", credentialCrypto.decrypt(source.getPassword()));
        return DatabaseConnectionSpec.fromMap(values);
    }

    public DatabaseConnectionSpec toActiveConnectionSpec(AgentDatabaseConnection source) {
        if (source == null) throw new IllegalArgumentException("数据库连接配置不存在");
        if (!Integer.valueOf(1).equals(source.getStatus())) {
            throw new IllegalArgumentException("数据库连接已停用");
        }
        return toConnectionSpec(source);
    }

    public Map<String, Object> toConnectionView(AgentDatabaseConnection source) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("connectionId", source.getConnectionId());
        view.put("connectionName", source.getConnectionName());
        view.put("agentId", source.getAgentId());
        view.put("connection", sanitizeConnectionView(
                DatabaseConnectionSpec.fromMap(storedConnectionValues(source)).toMap()));
        view.put("status", source.getStatus());
        view.put("testStatus", source.getTestStatus());
        view.put("lastTestTime", source.getLastTestTime());
        view.put("lastTestMessage", source.getLastTestMessage());
        view.put("timeoutSeconds", source.getTimeoutSeconds());
        view.put("description", source.getDescription());
        view.put("remark", source.getRemark());
        return view;
    }

    /**
     * Restores protected values omitted or masked by {@link #toConnectionView}
     * when an existing profile is edited. This keeps read responses
     * non-sensitive without turning masked placeholders into persisted
     * credentials.
     */
    public Map<String, Object> mergeProtectedValues(AgentDatabaseConnection source,
                                                    Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(incoming);
        if (source != null) mergeProtectedValues(storedConnectionValues(source), merged);
        return merged;
    }

    private Map<String, Object> storedConnectionValues(AgentDatabaseConnection source) {
        if (source == null) throw new IllegalArgumentException("数据库连接配置不存在");
        Map<String, Object> values = source.getConnectionSpec() == null || source.getConnectionSpec().isBlank()
                ? new LinkedHashMap<>()
                : PortableJsonCodec.decode(source.getConnectionSpec().getBytes(StandardCharsets.UTF_8));
        values.putIfAbsent("dialect", source.getDialect());
        values.put("username", source.getUsername());
        values.put("timeoutSeconds", source.getTimeoutSeconds());
        return values;
    }

    private Map<String, Object> sanitizeConnectionView(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!isSecretKey(key)) safe.put(key, sanitizeViewValue(value));
        });
        return safe;
    }

    @SuppressWarnings("unchecked")
    private void mergeProtectedValues(Map<String, Object> stored, Map<String, Object> incoming) {
        stored.forEach((key, storedValue) -> {
            if (isSecretKey(key)) {
                if (!incoming.containsKey(key)) incoming.put(key, storedValue);
                return;
            }
            Object incomingValue = incoming.get(key);
            if (storedValue instanceof Map<?, ?> storedMap
                    && incomingValue instanceof Map<?, ?> incomingMap) {
                Map<String, Object> writable = new LinkedHashMap<>();
                incomingMap.forEach((nestedKey, value) -> writable.put(String.valueOf(nestedKey), value));
                mergeProtectedValues((Map<String, Object>) storedMap, writable);
                incoming.put(key, writable);
                return;
            }
            if (storedValue instanceof String storedText
                    && incomingValue instanceof String incomingText
                    && incomingText.contains("***")
                    && incomingText.equals(sanitizeViewValue(storedText))) {
                incoming.put(key, storedText);
            }
        });
    }

    private Object sanitizeViewValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!isSecretKey(name)) safe.put(name, sanitizeViewValue(item));
            });
            return safe;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sanitizeViewValue).toList();
        if (value instanceof String text) {
            String sanitized = INLINE_SECRET.matcher(text).replaceAll("$1$2***");
            return AUTHORITY_PASSWORD.matcher(sanitized).replaceAll("$1***$2");
        }
        return value;
    }

    private boolean isSecretKey(String key) {
        String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pwd")
                || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("privatekey")
                || normalized.contains("accesskey") || normalized.contains("apikey");
    }
}
