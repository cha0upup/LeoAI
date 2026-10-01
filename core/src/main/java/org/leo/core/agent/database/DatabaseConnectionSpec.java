package org.leo.core.agent.database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime-neutral database connection description.
 *
 * <p>{@code dialect} controls SQL generation, while {@code connectionMode}
 * and {@code runtimeOptions} control how a concrete Agent runtime connects.
 * A custom connector may therefore reuse a built-in SQL dialect without
 * pretending to be that database vendor.</p>
 */
public final class DatabaseConnectionSpec {

    public static final String MODE_STANDARD = "standard";
    public static final String MODE_CUSTOM = "custom";

    private final String dialect;
    private final String connectionMode;
    private final String variant;
    private final String host;
    private final Integer port;
    private final String database;
    private final String service;
    private final String sid;
    private final String file;
    private final String username;
    private final String password;
    private final Integer timeoutSeconds;
    private final Map<String, Object> options;
    private final Map<String, Object> dialectOptions;
    private final Map<String, Object> runtimeOptions;

    public DatabaseConnectionSpec(String dialect,
                                  String connectionMode,
                                  String variant,
                                  String host,
                                  Integer port,
                                  String database,
                                  String service,
                                  String sid,
                                  String file,
                                  String username,
                                  String password,
                                  Integer timeoutSeconds,
                                  Map<String, Object> options,
                                  Map<String, Object> dialectOptions,
                                  Map<String, Object> runtimeOptions) {
        this.dialect = normalize(dialect);
        this.connectionMode = normalize(connectionMode);
        this.variant = normalize(variant);
        this.host = trimToNull(host);
        this.port = port;
        this.database = trimToNull(database);
        this.service = trimToNull(service);
        this.sid = trimToNull(sid);
        this.file = trimToNull(file);
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.timeoutSeconds = timeoutSeconds;
        this.options = immutableCopy(options);
        this.dialectOptions = immutableCopy(dialectOptions);
        this.runtimeOptions = immutableCopy(runtimeOptions);
        validate();
    }

    public static DatabaseConnectionSpec fromMap(Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("connection 不能为空");
        return new DatabaseConnectionSpec(
                string(source.get("dialect")),
                string(source.get("connectionMode")),
                string(source.get("variant")),
                string(source.get("host")),
                integer(source.get("port")),
                string(source.get("database")),
                string(source.get("service")),
                string(source.get("sid")),
                string(source.get("file")),
                string(source.get("username")),
                string(source.get("password")),
                integer(source.get("timeoutSeconds")),
                map(source.get("options")),
                map(source.get("dialectOptions")),
                map(source.get("runtimeOptions"))
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("dialect", dialect);
        result.put("connectionMode", connectionMode);
        result.put("variant", variant);
        result.put("host", host);
        result.put("port", port);
        result.put("database", database);
        result.put("service", service);
        result.put("sid", sid);
        result.put("file", file);
        result.put("username", username);
        result.put("password", password);
        result.put("timeoutSeconds", timeoutSeconds);
        result.put("options", options);
        result.put("dialectOptions", dialectOptions);
        result.put("runtimeOptions", runtimeOptions);
        return result;
    }

    public Map<String, Object> runtimeOptions(String runtime) {
        return map(runtimeOptions.get(normalize(runtime)));
    }

    public String getDialect() { return dialect; }
    public String getConnectionMode() { return connectionMode; }
    public String getVariant() { return variant; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public String getDatabase() { return database; }
    public String getService() { return service; }
    public String getSid() { return sid; }
    public String getFile() { return file; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public Map<String, Object> getOptions() { return options; }
    public Map<String, Object> getDialectOptions() { return dialectOptions; }
    public Map<String, Object> getRuntimeOptions() { return runtimeOptions; }

    private void validate() {
        if (dialect.isBlank()) throw new IllegalArgumentException("connection.dialect 不能为空");
        if (!MODE_STANDARD.equals(connectionMode) && !MODE_CUSTOM.equals(connectionMode)) {
            throw new IllegalArgumentException("connection.connectionMode 必须是 standard 或 custom");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 300)) {
            throw new IllegalArgumentException("connection.timeoutSeconds 必须在 1 到 300 之间");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("connection.port 无效");
        }

        if (MODE_CUSTOM.equals(connectionMode)) {
            validateCustomRuntimeOptions();
            return;
        }
        if ("generic".equals(dialect)) {
            throw new IllegalArgumentException("generic 方言必须使用 custom 连接模式");
        }
        if ("sqlite".equals(dialect)) {
            if (file == null) throw new IllegalArgumentException("SQLite connection.file 不能为空");
        } else if (host == null) {
            throw new IllegalArgumentException("connection.host 不能为空");
        }
    }

    private void validateCustomRuntimeOptions() {
        Map<String, Object> java = runtimeOptions("java");
        Map<String, Object> php = runtimeOptions("php");
        boolean javaConfigured = completePair(java, "driverClass", "jdbcUrl", "Java");
        boolean phpConfigured = completePair(php, "pdoDriver", "dsn", "PHP");
        if (!javaConfigured && !phpConfigured) {
            throw new IllegalArgumentException(
                    "custom 连接至少需要完整配置 Java(driverClass、jdbcUrl) 或 PHP(pdoDriver、dsn)");
        }
    }

    private boolean completePair(Map<String, Object> values,
                                 String firstKey,
                                 String secondKey,
                                 String runtime) {
        String first = string(values.get(firstKey));
        String second = string(values.get(secondKey));
        if (first.isBlank() && second.isBlank()) return false;
        if (first.isBlank() || second.isBlank()) {
            throw new IllegalArgumentException(runtime + " 自定义连接必须同时配置 "
                    + firstKey + " 和 " + secondKey);
        }
        return true;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        String text = string(value);
        if (text.isBlank()) return null;
        try { return Integer.valueOf(text); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("无效整数: " + text); }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
