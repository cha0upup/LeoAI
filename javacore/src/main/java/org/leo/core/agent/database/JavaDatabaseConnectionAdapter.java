package org.leo.core.agent.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Converts the shared connection description into Java/JDBC component input. */
public final class JavaDatabaseConnectionAdapter {

    public Map<String, Object> adapt(DatabaseConnectionSpec connection) {
        Map<String, Object> override = connection.runtimeOptions("java");
        String driverClass = text(override.get("driverClass"));
        String jdbcUrl = text(override.get("jdbcUrl"));
        if (DatabaseConnectionSpec.MODE_CUSTOM.equals(connection.getConnectionMode())) {
            if (driverClass.isBlank() || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("当前 Java Agent 未配置自定义 JDBC 连接");
            }
        } else {
            if (driverClass.isBlank()) driverClass = defaultDriver(connection.getDialect());
            if (jdbcUrl.isBlank()) jdbcUrl = buildUrl(connection);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", "jdbc");
        result.put("driverClass", driverClass);
        result.put("jdbcUrl", jdbcUrl);
        result.put("username", connection.getUsername());
        result.put("password", connection.getPassword());
        result.put("timeoutSeconds", connection.getTimeoutSeconds());
        result.put("connectionProperties", connectionProperties(connection, override));
        copyIfPresent(override, result, "queryTimeoutSeconds");
        copyIfPresent(override, result, "maxRows");
        copyIfPresent(override, result, "maxResultBytes");
        copyIfPresent(override, result, "maxCellBytes");
        copyIfPresent(override, result, "fetchSize");
        return result;
    }

    private Map<String, Object> connectionProperties(DatabaseConnectionSpec connection,
                                                     Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(connection.getOptions());
        result.putAll(map(override.get("properties")));
        result.putAll(map(override.get("connectionProperties")));
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    public String defaultDriver(String type) {
        return switch (type) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "oracle" -> "oracle.jdbc.driver.OracleDriver";
            case "dm" -> "dm.jdbc.driver.DmDriver";
            case "kingbasees" -> "com.kingbase8.Driver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> throw new IllegalArgumentException("Java 不支持数据库类型: " + type);
        };
    }

    private String buildUrl(DatabaseConnectionSpec connection) {
        String type = connection.getDialect();
        if ("sqlite".equals(type)) return "jdbc:sqlite:" + required(connection.getFile(), "connection.file");
        String host = required(connection.getHost(), "connection.host");
        int port = connection.getPort() == null ? defaultPort(type) : connection.getPort();
        String options = query(connection.getOptions());
        return switch (type) {
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/"
                    + path(connection.getDatabase()) + suffix(options, "?");
            case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/"
                    + path(connection.getDatabase()) + suffix(options, "?");
            case "sqlserver" -> "jdbc:sqlserver://" + host + ":" + port
                    + value(connection.getDatabase(), ";databaseName=") + sqlServerOptions(connection.getOptions());
            case "oracle" -> oracleUrl(connection, host, port) + suffix(options, "?");
            case "dm" -> "jdbc:dm://" + host + ":" + port + suffix(options, "?");
            case "kingbasees" -> "jdbc:kingbase8://" + host + ":" + port + "/"
                    + path(required(connection.getDatabase(), "connection.database")) + suffix(options, "?");
            default -> throw new IllegalArgumentException("Java 不支持数据库类型: " + type);
        };
    }

    private String oracleUrl(DatabaseConnectionSpec connection, String host, int port) {
        if ("sid".equals(connection.getVariant()) || connection.getSid() != null) {
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + required(connection.getSid(), "connection.sid");
        }
        String service = connection.getService() == null ? connection.getDatabase() : connection.getService();
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + required(service, "connection.service");
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "sqlserver" -> 1433;
            case "oracle" -> 1521;
            case "dm" -> 5236;
            case "kingbasees" -> 54321;
            default -> throw new IllegalArgumentException("数据库端口不能为空: " + type);
        };
    }

    private String query(Map<String, Object> options) {
        StringJoiner result = new StringJoiner("&");
        options.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                result.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        return result.toString();
    }

    private String sqlServerOptions(Map<String, Object> options) {
        StringBuilder result = new StringBuilder();
        options.forEach((key, value) -> {
            if (key != null && key.matches("[A-Za-z][A-Za-z0-9_]*") && value != null) {
                result.append(';').append(key).append('=').append(String.valueOf(value).replace(";", ""));
            }
        });
        return result.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String path(String value) { return value == null ? "" : value.replace("/", "%2F"); }
    private String value(String value, String prefix) { return value == null || value.isBlank() ? "" : prefix + value; }
    private String suffix(String value, String prefix) { return value.isBlank() ? "" : prefix + value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
