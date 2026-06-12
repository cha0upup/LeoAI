package org.leo.service.sql.dialect;


import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SqlDialectFactory {

    private final Map<String, AbstractSqlDialect> dialects = new LinkedHashMap<String, AbstractSqlDialect>();

    public SqlDialectFactory() {
        register(new MySqlDialect());
        register(new PostgreSqlDialect());
        register(new SqlServerDialect());
        register(new OracleDialect());
        register(new SqliteDialect());
    }

    public AbstractSqlDialect require(String type) {
        AbstractSqlDialect dialect = dialects.get(normalize(type));
        if (dialect == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
        return dialect;
    }

    public List<Map<String, Object>> getDialectInfos() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AbstractSqlDialect dialect : dialects.values()) {
            result.add(dialect.toInfo());
        }
        return result;
    }

    private void register(AbstractSqlDialect dialect) {
        dialects.put(dialect.getType(), dialect);
    }

    private String normalize(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("postgres".equals(value)) {
            return "postgresql";
        }
        if ("mariadb".equals(value)) {
            return "mysql";
        }
        if ("mssql".equals(value) || "ms".equals(value)) {
            return "sqlserver";
        }
        return value;
    }
}
