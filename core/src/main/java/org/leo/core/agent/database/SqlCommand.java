package org.leo.core.agent.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime-neutral SQL command. SQL text and bound values travel separately so
 * JDBC and PDO can share the same structured-query contract.
 */
public record SqlCommand(String sql, List<Object> parameters) {

    public SqlCommand {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        parameters = parameters == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<Object>(parameters));
    }

    public static SqlCommand raw(String sql) {
        return new SqlCommand(sql, List.of());
    }

    public static SqlCommand parameterized(String sql, List<Object> parameters) {
        return new SqlCommand(sql, parameters);
    }

    public boolean hasParameters() {
        return !parameters.isEmpty();
    }
}
