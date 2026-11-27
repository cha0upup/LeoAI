package org.leo.service.sql.dialect;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative registry for SQL dialect identifiers, aliases and capabilities.
 * HTTP, AI, persistence validation and SQL execution consume this same source.
 */
@Component
public class SqlDialectRegistry {

    private final Map<String, AbstractSqlDialect> dialects =
            new LinkedHashMap<String, AbstractSqlDialect>();
    private final Map<String, String> aliases = new LinkedHashMap<String, String>();
    private final List<Map<String, Object>> dialectInfos;

    public SqlDialectRegistry() {
        register(new MySqlDialect(), "mariadb");
        register(new PostgreSqlDialect(), "postgres");
        register(new SqlServerDialect(), "mssql", "ms");
        register(new OracleDialect());
        register(new DmSqlDialect(), "dameng");
        register(new KingbaseEsDialect(), "kingbase", "kes");
        register(new SqliteDialect());
        register(new GenericSqlDialect());
        dialectInfos = buildDialectInfos();
    }

    public AbstractSqlDialect require(String type) {
        AbstractSqlDialect dialect = dialects.get(canonicalType(type));
        if (dialect == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + type
                    + "。未内置的数据库请使用 dialect=generic、connectionMode=custom，"
                    + "并配置 runtimeOptions.java(driverClass、jdbcUrl) 或 "
                    + "runtimeOptions.php(pdoDriver、dsn)");
        }
        return dialect;
    }

    public boolean supports(String type) {
        return dialects.containsKey(canonicalType(type));
    }

    public String canonicalType(String type) {
        String normalized = normalize(type);
        return aliases.getOrDefault(normalized, normalized);
    }

    public List<String> getSupportedTypes() {
        return List.copyOf(dialects.keySet());
    }

    public List<Map<String, Object>> getDialectInfos() {
        return dialectInfos;
    }

    private List<Map<String, Object>> buildDialectInfos() {
        List<Map<String, Object>> result = new ArrayList<>(dialects.size());
        for (Map.Entry<String, AbstractSqlDialect> entry : dialects.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<String, Object>(entry.getValue().toInfo());
            info.put("aliases", aliasesFor(entry.getKey()));
            info.put("connectionModes", "generic".equals(entry.getKey())
                    ? List.of("custom") : List.of("standard", "custom"));
            result.add(Collections.unmodifiableMap(info));
        }
        return List.copyOf(result);
    }

    private void register(AbstractSqlDialect dialect, String... dialectAliases) {
        String type = normalize(dialect.getType());
        if (type.isBlank()) throw new IllegalArgumentException("方言 type 不能为空");
        if (dialects.putIfAbsent(type, dialect) != null) {
            throw new IllegalStateException("数据库方言重复注册: " + type);
        }
        aliases.put(type, type);
        for (String alias : dialectAliases) {
            String normalized = normalize(alias);
            String previous = aliases.putIfAbsent(normalized, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalStateException("数据库方言别名重复注册: " + normalized);
            }
        }
    }

    private List<String> aliasesFor(String type) {
        return aliases.entrySet().stream()
                .filter(entry -> entry.getValue().equals(type) && !entry.getKey().equals(type))
                .map(Map.Entry::getKey)
                .toList();
    }

    private String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }
}
