package org.leo.phpcore.database;

import org.leo.core.agent.database.DatabaseConnectionSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts the shared connection description into PHP/PDO component input. */
public final class PhpDatabaseConnectionAdapter {

    public Map<String, Object> adapt(DatabaseConnectionSpec connection) {
        Map<String, Object> override = connection.runtimeOptions("php");
        String pdoDriver = text(override.get("pdoDriver"));
        String dsn = text(override.get("dsn"));
        if (DatabaseConnectionSpec.MODE_CUSTOM.equals(connection.getConnectionMode())) {
            if (pdoDriver.isBlank() || dsn.isBlank()) {
                throw new IllegalArgumentException("当前 PHP Agent 未配置自定义 PDO 连接");
            }
        } else {
            if (pdoDriver.isBlank()) pdoDriver = defaultDriver(connection.getDialect());
            if (dsn.isBlank()) dsn = buildDsn(connection, pdoDriver);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", "pdo");
        result.put("pdoDriver", pdoDriver);
        result.put("dsn", dsn);
        result.put("username", connection.getUsername());
        result.put("password", connection.getPassword());
        result.put("timeoutSeconds", connection.getTimeoutSeconds());
        copyIfPresent(override, result, "maxRows");
        copyIfPresent(override, result, "maxResultBytes");
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    public String defaultDriver(String type) {
        return switch (type) {
            case "mysql" -> "mysql";
            case "postgresql" -> "pgsql";
            case "sqlserver" -> "sqlsrv";
            case "oracle" -> "oci";
            case "sqlite" -> "sqlite";
            default -> throw new IllegalArgumentException("PHP 不支持数据库类型: " + type);
        };
    }

    private String buildDsn(DatabaseConnectionSpec connection, String pdoDriver) {
        String dialect = connection.getDialect();
        if ("sqlite".equals(dialect)) {
            return "sqlite:" + required(connection.getFile(), "connection.file");
        }
        String host = clean(required(connection.getHost(), "connection.host"));
        int port = connection.getPort() == null ? defaultPort(dialect) : connection.getPort();
        String database = clean(connection.getDatabase());
        return switch (dialect) {
            case "mysql" -> "mysql:host=" + host + ";port=" + port
                    + optional(";dbname=", database) + ";charset=" + mysqlCharset(connection.getOptions());
            case "postgresql" -> "pgsql:host=" + host + ";port=" + port
                    + optional(";dbname=", database) + pgOptions(connection.getOptions());
            case "sqlserver" -> sqlServerDsn(pdoDriver, host, port, database, connection.getOptions());
            case "oracle" -> "oci:dbname=" + oracleTarget(connection, host, port) + ";charset=AL32UTF8";
            default -> throw new IllegalArgumentException("PHP 不支持数据库方言: " + connection.getDialect());
        };
    }

    private String sqlServerDsn(String driver, String host, int port, String database, Map<String, Object> options) {
        if ("dblib".equals(driver)) {
            return "dblib:host=" + host + ":" + port + optional(";dbname=", database) + ";charset=UTF-8";
        }
        StringBuilder dsn = new StringBuilder("sqlsrv:Server=").append(host).append(',').append(port);
        if (database != null && !database.isBlank()) dsn.append(";Database=").append(database);
        appendOption(dsn, options, "encrypt", "Encrypt");
        appendOption(dsn, options, "trustServerCertificate", "TrustServerCertificate");
        return dsn.toString();
    }

    private String oracleTarget(DatabaseConnectionSpec connection, String host, int port) {
        if ("sid".equals(connection.getVariant()) || connection.getSid() != null) {
            return host + ":" + port + "/" + clean(required(connection.getSid(), "connection.sid"));
        }
        String service = connection.getService() == null ? connection.getDatabase() : connection.getService();
        return "//" + host + ":" + port + "/" + clean(required(service, "connection.service"));
    }

    private String pgOptions(Map<String, Object> options) {
        StringBuilder result = new StringBuilder();
        appendOption(result, options, "sslmode", "sslmode");
        appendOption(result, options, "applicationName", "application_name");
        appendOption(result, options, "connectTimeout", "connect_timeout");
        return result.toString();
    }

    private String mysqlCharset(Map<String, Object> options) {
        String charset = text(options.get("charset"));
        return charset.matches("[A-Za-z0-9_-]+") ? charset : "utf8mb4";
    }

    private void appendOption(StringBuilder target, Map<String, Object> options, String source, String name) {
        Object value = options.get(source);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.append(';').append(name).append('=').append(clean(String.valueOf(value)));
        }
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "sqlserver" -> 1433;
            case "oracle" -> 1521;
            default -> throw new IllegalArgumentException("数据库端口不能为空: " + type);
        };
    }

    private String clean(String value) { return value == null ? null : value.replace(";", "").replace("\0", ""); }
    private String optional(String prefix, String value) { return value == null || value.isBlank() ? "" : prefix + value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
