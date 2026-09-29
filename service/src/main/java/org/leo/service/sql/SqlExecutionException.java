package org.leo.service.sql;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves the structured database error returned by a Puppet runtime. */
public final class SqlExecutionException extends Exception {

    private final int statusCode;
    private final String errorCategory;
    private final String sqlState;
    private final boolean retryable;
    private final Integer vendorCode;

    public SqlExecutionException(int statusCode,
                                 String message,
                                 String errorCategory,
                                 String sqlState,
                                 boolean retryable,
                                 Integer vendorCode) {
        super(message == null || message.isBlank() ? "数据库执行失败" : message);
        this.statusCode = statusCode;
        this.errorCategory = normalize(errorCategory, "SQL_ERROR");
        this.sqlState = normalize(sqlState, null);
        this.retryable = retryable;
        this.vendorCode = vendorCode;
    }

    public static SqlExecutionException fromResult(Map<String, Object> result) {
        return new SqlExecutionException(
                integer(result.get("code"), 500),
                string(result.get("msg")),
                string(result.get("errorCategory")),
                string(result.get("sqlState")),
                bool(result.get("retryable")),
                nullableInteger(result.get("vendorCode")));
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("databaseCode", statusCode);
        details.put("errorCategory", errorCategory);
        details.put("retryable", retryable);
        if (sqlState != null) details.put("sqlState", sqlState);
        if (vendorCode != null) details.put("vendorCode", vendorCode);
        return details;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
