package org.leo.service.sql.dialect;

import java.util.*;

public abstract class AbstractSqlDialect {

    public abstract String getType();

    public abstract String getName();

    public abstract Integer getDefaultPort();

    public abstract String getDriverClass();

    public abstract String getUrlTemplate();

    public abstract List<Map<String, Object>> getVariants();

    public abstract List<Map<String, Object>> getDataTypes();

    public abstract String buildTestSql();

    public abstract String buildDatabasesSql();

    public abstract String buildTablesSql(String database);

    public abstract String buildTableColumnsSql(String database, String table);

    protected abstract String buildQualifiedTable(String database, String table);

    protected abstract String buildPaginationSql(String baseSql, int offset, int pageSize);

    protected abstract String escapeIdentifier(String identifier);

    public String buildCountSql(String database, String table, List<Map<String, Object>> filters) {
        return "SELECT COUNT(*) AS total FROM " + buildQualifiedTable(database, table) + buildWhereClause(filters);
    }

    public String buildQueryTableSql(String database,
                                     String table,
                                     int page,
                                     int pageSize,
                                     List<String> columns,
                                     List<Map<String, Object>> orderBy,
                                     List<Map<String, Object>> filters) {
        String selectColumns = buildSelectColumns(columns);
        String baseSql = "SELECT " + selectColumns + " FROM " + buildQualifiedTable(database, table)
                + buildWhereClause(filters) + buildOrderByClause(orderBy);
        return buildPaginationSql(baseSql, Math.max(0, (page - 1) * pageSize), pageSize);
    }

    public String buildCreateTableSql(String database, String table, List<Map<String, Object>> columns) {
        List<String> definitions = new ArrayList<String>();
        List<String> primaryKeys = new ArrayList<String>();
        for (Map<String, Object> column : columns) {
            String name = stringValue(column.get("name"));
            String type = stringValue(column.get("type"));
            if (isBlank(name) || isBlank(type)) {
                continue;
            }
            boolean nullable = toBoolean(column.get("nullable"), true);
            boolean primaryKey = toBoolean(column.get("primaryKey"), false);
            Object defaultValue = column.get("defaultValue");
            StringBuilder definition = new StringBuilder();
            definition.append(escapeIdentifier(name)).append(" ").append(type.trim());
            if (!nullable || primaryKey) {
                definition.append(" NOT NULL");
            }
            if (defaultValue != null) {
                definition.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
            }
            definitions.add(definition.toString());
            if (primaryKey) {
                primaryKeys.add(escapeIdentifier(name));
            }
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("columns 不能为空");
        }
        if (!primaryKeys.isEmpty()) {
            definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        return "CREATE TABLE " + buildQualifiedTable(database, table) + " (\n  " + String.join(",\n  ", definitions) + "\n)";
    }

    public String buildCreateDatabaseSql(String database) {
        if (isBlank(database)) {
            throw new IllegalArgumentException("database 不能为空");
        }
        return "CREATE DATABASE " + escapeIdentifier(database);
    }

    public String buildInsertSql(String database, String table, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("row 不能为空");
        }
        List<String> fields = new ArrayList<String>();
        List<String> values = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (isBlank(entry.getKey())) {
                continue;
            }
            fields.add(escapeIdentifier(entry.getKey()));
            values.add(formatLiteral(entry.getValue()));
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("row 不能为空");
        }
        return "INSERT INTO " + buildQualifiedTable(database, table) + " (" + String.join(", ", fields) + ") VALUES (" + String.join(", ", values) + ")";
    }

    public String buildUpdateSql(String database, String table, Map<String, Object> where, Map<String, Object> update) {
        if (update == null || update.isEmpty()) {
            throw new IllegalArgumentException("update 不能为空");
        }
        List<String> sets = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            if (isBlank(entry.getKey())) {
                continue;
            }
            sets.add(escapeIdentifier(entry.getKey()) + " = " + formatLiteral(entry.getValue()));
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("update 不能为空");
        }
        String whereClause = buildStructuredWhereClause(where);
        if (isBlank(whereClause)) {
            throw new IllegalArgumentException("where 不能为空");
        }
        return "UPDATE " + buildQualifiedTable(database, table) + " SET " + String.join(", ", sets) + whereClause;
    }

    public String buildDeleteSql(String database, String table, Map<String, Object> where) {
        String whereClause = buildStructuredWhereClause(where);
        if (isBlank(whereClause)) {
            throw new IllegalArgumentException("where 不能为空");
        }
        return "DELETE FROM " + buildQualifiedTable(database, table) + whereClause;
    }

    public Map<String, Object> toInfo() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", getType());
        item.put("name", getName());
        item.put("defaultPort", getDefaultPort());
        item.put("driverClass", getDriverClass());
        item.put("urlTemplate", getUrlTemplate());
        item.put("variants", getVariants());
        return item;
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

    protected String buildWhereClause(List<Map<String, Object>> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> conditions = new ArrayList<String>();
        for (Map<String, Object> filter : filters) {
            String field = stringValue(filter.get("field"));
            String operator = stringValue(filter.get("operator"));
            Object value = filter.get("value");
            if (isBlank(field) || isBlank(operator)) {
                continue;
            }
            String escapedField = escapeIdentifier(field);
            String normalized = operator.trim().toLowerCase(Locale.ROOT);
            if ("is_null".equals(normalized)) {
                conditions.add(escapedField + " IS NULL");
            } else if ("is_not_null".equals(normalized)) {
                conditions.add(escapedField + " IS NOT NULL");
            } else if ("in".equals(normalized) || "not_in".equals(normalized)) {
                List<Object> values = toList(value);
                if (values.isEmpty()) {
                    continue;
                }
                List<String> items = new ArrayList<String>();
                for (Object item : values) {
                    items.add(formatLiteral(item));
                }
                conditions.add(escapedField + ("not_in".equals(normalized) ? " NOT IN (" : " IN (") + String.join(", ", items) + ")");
            } else if ("like".equals(normalized)) {
                conditions.add(escapedField + " LIKE " + formatLiteral(value));
            } else {
                conditions.add(escapedField + " " + normalizeOperator(normalized) + " " + formatLiteral(value));
            }
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    @SuppressWarnings("unchecked")
    protected String buildStructuredWhereClause(Map<String, Object> where) {
        if (where == null || where.isEmpty()) {
            return "";
        }
        String type = stringValue(where.get("type"));
        if ("pk".equalsIgnoreCase(type)) {
            Object values = where.get("values");
            if (values instanceof Map<?, ?>) {
                return " WHERE " + joinEqualsConditions((Map<String, Object>) values, " AND ");
            }
        }
        if ("pk_list".equalsIgnoreCase(type)) {
            Object items = where.get("items");
            if (items instanceof List<?>) {
                List<?> list = (List<?>) items;
                List<String> groups = new ArrayList<String>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?>) {
                        String condition = joinEqualsConditions((Map<String, Object>) item, " AND ");
                        if (!isBlank(condition)) {
                            groups.add("(" + condition + ")");
                        }
                    }
                }
                return groups.isEmpty() ? "" : " WHERE " + String.join(" OR ", groups);
            }
        }
        Object filters = where.get("filters");
        if (filters instanceof List<?>) {
            return buildWhereClause((List<Map<String, Object>>) filters);
        }
        return "";
    }

    protected String joinEqualsConditions(Map<String, Object> values, String joiner) {
        List<String> conditions = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isBlank(entry.getKey())) {
                continue;
            }
            conditions.add(escapeIdentifier(entry.getKey()) + " = " + formatLiteral(entry.getValue()));
        }
        return conditions.isEmpty() ? "" : String.join(joiner, conditions);
    }

    protected String normalizeOperator(String operator) {
        if ("eq".equals(operator)) {
            return "=";
        }
        if ("ne".equals(operator)) {
            return "<>";
        }
        if ("gt".equals(operator)) {
            return ">";
        }
        if ("gte".equals(operator)) {
            return ">=";
        }
        if ("lt".equals(operator)) {
            return "<";
        }
        if ("lte".equals(operator)) {
            return "<=";
        }
        return "=";
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

    protected Map<String, Object> variant(String key, String name, String driverClass, String urlTemplate) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", key);
        item.put("name", name);
        item.put("driverClass", driverClass);
        item.put("urlTemplate", urlTemplate);
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
}
