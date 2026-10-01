package org.leo.service.sql.dialect;

import org.leo.core.agent.database.SqlCommand;
import org.leo.service.sql.SqlObjectRef;

import java.util.*;

public abstract class AbstractSqlDialect {

    private static final int MAX_FILTERS = 256;
    private static final int MAX_ORDER_BY = 16;

    public abstract String getType();

    public abstract String getName();

    public abstract Integer getDefaultPort();

    public abstract List<Map<String, Object>> getVariants();

    public abstract List<Map<String, Object>> getDataTypes();

    public abstract String buildTestSql();

    public abstract String buildDatabasesSql();

    public abstract String buildTablesSql(SqlObjectRef namespace);

    public abstract String buildTableColumnsSql(SqlObjectRef table);

    /** Ordered namespace levels exposed by this dialect in the object explorer. */
    public List<String> getNamespaceLevels() {
        return List.of("catalog");
    }

    public SqlObjectRef namespaceRef(String name) {
        if (getNamespaceLevels().contains("schema") && !getNamespaceLevels().contains("catalog")) {
            return SqlObjectRef.schema(name);
        }
        return SqlObjectRef.catalog(name);
    }

    public SqlObjectRef tableRef(String namespace, String schema, String table) {
        List<String> levels = getNamespaceLevels();
        if (levels.contains("catalog") && levels.contains("schema")) {
            return SqlObjectRef.table(namespace, schema, table);
        }
        if (levels.contains("schema")) {
            return SqlObjectRef.table(null, schema == null || schema.isBlank() ? namespace : schema, table);
        }
        return SqlObjectRef.table(namespace, null, table);
    }

    public SqlDialectCapabilities getCapabilities() {
        return SqlDialectCapabilities.managed(true);
    }

    public Map<String, Boolean> getRuntimeSupport() {
        return Map.of("java", true, "php", true);
    }

    protected abstract String buildQualifiedTable(SqlObjectRef table);

    protected abstract String buildPaginationSql(String baseSql, int offset, int pageSize);

    protected abstract String escapeIdentifier(String identifier);

    public SqlCommand buildCountCommand(SqlObjectRef table, List<Map<String, Object>> filters) {
        BoundClause where = buildBoundWhereClause(filters);
        return SqlCommand.parameterized(
                "SELECT COUNT(*) AS total FROM " + buildQualifiedTable(table) + where.sql(),
                where.parameters());
    }

    public SqlCommand buildQueryTableCommand(SqlObjectRef table,
                                             int page,
                                             int pageSize,
                                             List<String> columns,
                                             List<Map<String, Object>> orderBy,
                                             List<Map<String, Object>> filters) {
        BoundClause where = buildBoundWhereClause(filters);
        String baseSql = "SELECT " + buildSelectColumns(columns) + " FROM " + buildQualifiedTable(table)
                + where.sql() + buildOrderByClause(orderBy);
        return SqlCommand.parameterized(
                buildPaginationSql(baseSql, Math.max(0, (page - 1) * pageSize), pageSize),
                where.parameters());
    }

    public String buildCreateTableSql(SqlObjectRef table, List<Map<String, Object>> columns) {
        List<String> definitions = new ArrayList<String>();
        List<String> primaryKeys = new ArrayList<String>();
        appendColumnDefinitions(columns, definitions, primaryKeys);
        return "CREATE TABLE " + buildQualifiedTable(table) + " (\n  " + String.join(",\n  ", definitions) + "\n)";
    }

    public String buildCreateDatabaseSql(String database) {
        if (isBlank(database)) {
            throw new IllegalArgumentException("database 不能为空");
        }
        return "CREATE DATABASE " + escapeIdentifier(database);
    }

    public SqlCommand buildInsertCommand(SqlObjectRef table, Map<String, Object> row) {
        if (row == null || row.isEmpty()) throw new IllegalArgumentException("row 不能为空");
        List<String> fields = new ArrayList<String>();
        List<String> placeholders = new ArrayList<String>();
        List<Object> parameters = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (isBlank(entry.getKey())) continue;
            fields.add(escapeIdentifier(entry.getKey()));
            placeholders.add("?");
            parameters.add(entry.getValue());
        }
        if (fields.isEmpty()) throw new IllegalArgumentException("row 不能为空");
        return SqlCommand.parameterized(
                "INSERT INTO " + buildQualifiedTable(table) + " (" + String.join(", ", fields)
                        + ") VALUES (" + String.join(", ", placeholders) + ")",
                parameters);
    }

    public SqlCommand buildUpdateCommand(SqlObjectRef table,
                                         Map<String, Object> where,
                                         Map<String, Object> update) {
        if (update == null || update.isEmpty()) throw new IllegalArgumentException("update 不能为空");
        List<String> sets = new ArrayList<String>();
        List<Object> parameters = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            if (isBlank(entry.getKey())) continue;
            sets.add(escapeIdentifier(entry.getKey()) + " = ?");
            parameters.add(entry.getValue());
        }
        if (sets.isEmpty()) throw new IllegalArgumentException("update 不能为空");
        BoundClause whereClause = buildBoundStructuredWhereClause(where);
        if (isBlank(whereClause.sql())) throw new IllegalArgumentException("where 不能为空");
        parameters.addAll(whereClause.parameters());
        return SqlCommand.parameterized(
                "UPDATE " + buildQualifiedTable(table) + " SET " + String.join(", ", sets) + whereClause.sql(),
                parameters);
    }

    public SqlCommand buildDeleteCommand(SqlObjectRef table, Map<String, Object> where) {
        BoundClause whereClause = buildBoundStructuredWhereClause(where);
        if (isBlank(whereClause.sql())) throw new IllegalArgumentException("where 不能为空");
        return SqlCommand.parameterized(
                "DELETE FROM " + buildQualifiedTable(table) + whereClause.sql(),
                whereClause.parameters());
    }

    public Map<String, Object> toInfo() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", getType());
        item.put("name", getName());
        item.put("defaultPort", getDefaultPort());
        item.put("variants", immutableMetadataList(getVariants()));
        item.put("dataTypes", immutableMetadataList(getDataTypes()));
        item.put("namespaceLevels", List.copyOf(getNamespaceLevels()));
        item.put("runtimeSupport", Collections.unmodifiableMap(
                new LinkedHashMap<String, Boolean>(getRuntimeSupport())));
        item.put("capabilities", getCapabilities().toMap());
        return item;
    }

    private void appendColumnDefinitions(List<Map<String, Object>> columns,
                                         List<String> definitions,
                                         List<String> primaryKeys) {
        if (columns != null) {
            for (Map<String, Object> column : columns) {
                String name = stringValue(column.get("name"));
                String type = stringValue(column.get("type"));
                if (isBlank(name) || isBlank(type)) continue;
                boolean nullable = toBoolean(column.get("nullable"), true);
                boolean primaryKey = toBoolean(column.get("primaryKey"), false);
                Object defaultValue = column.get("defaultValue");
                StringBuilder definition = new StringBuilder();
                definition.append(escapeIdentifier(name)).append(" ").append(type.trim());
                if (!nullable || primaryKey) definition.append(" NOT NULL");
                if (defaultValue != null) definition.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
                definitions.add(definition.toString());
                if (primaryKey) primaryKeys.add(escapeIdentifier(name));
            }
        }
        if (definitions.isEmpty()) throw new IllegalArgumentException("columns 不能为空");
        if (!primaryKeys.isEmpty()) definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
    }

    protected String buildSelectColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        List<String> result = new ArrayList<String>();
        for (String column : columns) {
            if (!isBlank(column)) {
                result.add(escapeIdentifier(column));
            }
        }
        return result.isEmpty() ? "*" : String.join(", ", result);
    }

    protected String buildOrderByClause(List<Map<String, Object>> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return "";
        }
        if (orderBy.size() > MAX_ORDER_BY) {
            throw new IllegalArgumentException("排序字段数量超过上限 " + MAX_ORDER_BY);
        }
        List<String> parts = new ArrayList<String>();
        for (Map<String, Object> item : orderBy) {
            String field = stringValue(item.get("field"));
            if (isBlank(field)) {
                continue;
            }
            String direction = "DESC".equalsIgnoreCase(stringValue(item.get("direction"))) ? "DESC" : "ASC";
            parts.add(escapeIdentifier(field) + " " + direction);
        }
        return parts.isEmpty() ? "" : " ORDER BY " + String.join(", ", parts);
    }

    protected BoundClause buildBoundWhereClause(List<Map<String, Object>> filters) {
        if (filters == null || filters.isEmpty()) return BoundClause.empty();
        requireFilterLimit(filters);
        List<String> conditions = new ArrayList<String>();
        List<Object> parameters = new ArrayList<Object>();
        for (Map<String, Object> filter : filters) {
            String field = stringValue(filter.get("field"));
            String operator = stringValue(filter.get("operator"));
            Object value = filter.get("value");
            if (isBlank(field) || isBlank(operator)) continue;

            String escapedField = escapeIdentifier(field);
            String normalized = operator.trim().toLowerCase(Locale.ROOT);
            if ("is_null".equals(normalized)) {
                conditions.add(escapedField + " IS NULL");
            } else if ("is_not_null".equals(normalized)) {
                conditions.add(escapedField + " IS NOT NULL");
            } else if ("in".equals(normalized) || "not_in".equals(normalized)) {
                List<Object> values = toList(value);
                if (values.isEmpty()) {
                    conditions.add("in".equals(normalized) ? "1 = 0" : "1 = 1");
                    continue;
                }
                conditions.add(escapedField + ("not_in".equals(normalized) ? " NOT IN (" : " IN (")
                        + String.join(", ", Collections.nCopies(values.size(), "?")) + ")");
                parameters.addAll(values);
            } else if (("eq".equals(normalized) || "=".equals(normalized)) && value == null) {
                conditions.add(escapedField + " IS NULL");
            } else if (("ne".equals(normalized) || "<>".equals(normalized) || "!=".equals(normalized))
                    && value == null) {
                conditions.add(escapedField + " IS NOT NULL");
            } else {
                conditions.add(escapedField + ("like".equals(normalized)
                        ? " LIKE ?" : " " + normalizeOperator(normalized) + " ?"));
                parameters.add(value);
            }
        }
        return conditions.isEmpty()
                ? BoundClause.empty()
                : new BoundClause(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    @SuppressWarnings("unchecked")
    protected BoundClause buildBoundStructuredWhereClause(Map<String, Object> where) {
        if (where == null || where.isEmpty()) return BoundClause.empty();
        String type = stringValue(where.get("type"));
        if ("pk".equalsIgnoreCase(type)) {
            Object values = where.get("values");
            if (values instanceof Map<?, ?>) {
                BoundClause clause = buildBoundEqualsConditions((Map<String, Object>) values, " AND ");
                return clause.sql().isEmpty() ? clause : clause.withPrefix(" WHERE ");
            }
        }
        if ("pk_list".equalsIgnoreCase(type)) {
            Object items = where.get("items");
            if (items instanceof List<?>) {
                List<String> groups = new ArrayList<String>();
                List<Object> parameters = new ArrayList<Object>();
                for (Object item : (List<?>) items) {
                    if (!(item instanceof Map<?, ?>)) continue;
                    BoundClause clause = buildBoundEqualsConditions((Map<String, Object>) item, " AND ");
                    if (!clause.sql().isEmpty()) {
                        groups.add("(" + clause.sql() + ")");
                        parameters.addAll(clause.parameters());
                    }
                }
                return groups.isEmpty() ? BoundClause.empty()
                        : new BoundClause(" WHERE " + String.join(" OR ", groups), parameters);
            }
        }
        Object filters = where.get("filters");
        if (filters instanceof List<?>) {
            return buildBoundWhereClause((List<Map<String, Object>>) filters);
        }
        return BoundClause.empty();
    }

    protected BoundClause buildBoundEqualsConditions(Map<String, Object> values, String joiner) {
        List<String> conditions = new ArrayList<String>();
        List<Object> parameters = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isBlank(entry.getKey())) continue;
            if (entry.getValue() == null) {
                conditions.add(escapeIdentifier(entry.getKey()) + " IS NULL");
            } else {
                conditions.add(escapeIdentifier(entry.getKey()) + " = ?");
                parameters.add(entry.getValue());
            }
        }
        return new BoundClause(String.join(joiner, conditions), parameters);
    }

    protected record BoundClause(String sql, List<Object> parameters) {
        protected BoundClause {
            sql = sql == null ? "" : sql;
            parameters = parameters == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<Object>(parameters));
        }

        protected static BoundClause empty() {
            return new BoundClause("", List.of());
        }

        protected BoundClause withPrefix(String prefix) {
            return new BoundClause(prefix + sql, parameters);
        }
    }

    protected String normalizeOperator(String operator) {
        if ("eq".equals(operator) || "=".equals(operator)) return "=";
        if ("ne".equals(operator) || "!=".equals(operator) || "<>".equals(operator)) return "<>";
        if ("gt".equals(operator) || ">".equals(operator)) return ">";
        if ("gte".equals(operator) || ">=".equals(operator)) return ">=";
        if ("lt".equals(operator) || "<".equals(operator)) return "<";
        if ("lte".equals(operator) || "<=".equals(operator)) return "<=";
        throw new IllegalArgumentException("不支持的筛选操作符: " + operator);
    }

    protected String formatDefaultValue(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return "NULL";
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (isNumeric(text) || "NULL".equals(upper) || "CURRENT_TIMESTAMP".equals(upper)
                || "CURRENT_DATE".equals(upper) || "CURRENT_TIME".equals(upper)
                || "TRUE".equals(upper) || "FALSE".equals(upper)) {
            return text;
        }
        return formatLiteral(text);
    }

    protected String formatLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        return "'" + text.replace("'", "''") + "'";
    }

    private List<Map<String, Object>> immutableMetadataList(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(source.size());
        for (Map<String, Object> item : source) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>(item);
            Object fields = copy.get("fields");
            if (fields instanceof List<?>) {
                copy.put("fields", List.copyOf((List<?>) fields));
            }
            result.add(Collections.unmodifiableMap(copy));
        }
        return List.copyOf(result);
    }

    protected Map<String, Object> variant(String key, String name, String... fields) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", key);
        item.put("name", name);
        item.put("fields", Arrays.asList(fields));
        return item;
    }

    protected Map<String, Object> dataType(String type, Integer defaultLength, Integer defaultPrecision, Integer defaultScale) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", type);
        item.put("defaultLength", defaultLength);
        item.put("defaultPrecision", defaultPrecision);
        item.put("defaultScale", defaultScale);
        return item;
    }

    protected String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text);
    }

    protected boolean isNumeric(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    protected List<Object> toList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?>) {
            return new ArrayList<Object>((List<?>) value);
        }
        return Collections.<Object>singletonList(value);
    }

    private void requireFilterLimit(List<Map<String, Object>> filters) {
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("筛选条件数量超过上限 " + MAX_FILTERS);
        }
    }
}
