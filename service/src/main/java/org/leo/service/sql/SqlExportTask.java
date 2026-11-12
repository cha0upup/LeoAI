package org.leo.service.sql;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Owns export metadata, state transitions and execution-attempt identity; no scheduling or artifact I/O.
 * Mutable state and attempt checks share this object's monitor, so replacement fences late callbacks.
 */
final class SqlExportTask {
    private final Map<String, Object> data;
    private final SqlExportTaskRepository repository;
    private Attempt attempt;

    private SqlExportTask(SqlExportTaskRepository repository, Map<String, Object> data) {
        this.repository = repository;
        this.data = copy(data);
    }

    static SqlExportTask table(SqlExportTaskRepository repository, String taskId, String userId, String sessionId,
                               Path output, String downloadPath, SqlObjectRef table, String format) {
        Map<String, Object> data = createTask(taskId, userId, sessionId, "TABLE_EXPORT",
                output.getFileName().toString(), output, table.namespace(), table.name(), downloadPath);
        data.put("objectRef", table.toMap());
        data.put("format", format);
        return new SqlExportTask(repository, data);
    }

    static SqlExportTask database(SqlExportTaskRepository repository, String taskId, String userId, String sessionId,
                                  Path output, String downloadPath, SqlObjectRef namespace, List<SqlObjectRef> tables,
                                  boolean structure, boolean rows, String format) {
        Map<String, Object> data = createTask(taskId, userId, sessionId, "DATABASE_EXPORT",
                output.getFileName().toString(), output, namespace.namespace(), null, downloadPath);
        data.put("format", format);
        data.put("includeStructure", structure);
        data.put("includeData", rows);
        data.put("objectRef", namespace.toMap());
        data.put("tableRefs", toRefMaps(tables));
        return new SqlExportTask(repository, data);
    }

    static SqlExportTask restore(SqlExportTaskRepository repository, SqlExportTaskKey key) throws Exception {
        return new SqlExportTask(repository, key.requireOwner(repository.load(key)));
    }

    synchronized SqlExportTask requireOwner(SqlExportTaskKey key) {
        key.requireOwner(data);
        return this;
    }

    synchronized String taskId() { return String.valueOf(data.get("taskId")); }
    synchronized String userId() { return String.valueOf(data.get("userId")); }
    synchronized SqlExportTaskKey key() { return new SqlExportTaskKey(userId(), taskId()); }
    synchronized String sessionId() { return String.valueOf(data.get("sessionId")); }
    synchronized String type() { return String.valueOf(data.get("taskType")); }
    synchronized String finalPath() { return String.valueOf(data.get("finalPath")); }
    synchronized long createdTime() { return toLong(data.get("createdTime")); }
    synchronized int processedTables() { return toInt(data.get("processedTables")); }
    synchronized SqlObjectRef objectRef() { return refValue(data.get("objectRef")); }
    synchronized List<SqlObjectRef> selectedTables() { return refListValue(data.get("tableRefs")); }
    synchronized boolean includeStructure() { return toBoolean(data.get("includeStructure")); }
    synchronized boolean includeData() { return toBoolean(data.get("includeData")); }

    synchronized Attempt beginAttempt() {
        Attempt previous = attempt;
        attempt = new Attempt();
        if (previous != null) previous.cancelRequested = true;
        return attempt;
    }

    synchronized boolean isCurrent(Attempt expected) { return expected != null && attempt == expected; }
    synchronized void finishAttempt(Attempt expected) { if (attempt == expected) attempt = null; }
    synchronized void clearAttempt() { attempt = null; }

    synchronized void signalCancellation() {
        Attempt current = attempt;
        if (current != null) {
            current.cancelRequested = true;
            current.pauseRequested = false;
        }
    }

    synchronized void pause() {
        if (isTerminalStatus(status())) return;
        Attempt current = attempt;
        if (current == null) {
            data.put("status", "PAUSED");
            persist();
        } else current.pauseRequested = true;
    }

    synchronized void cancel() {
        if (isTerminalStatus(status())) return;
        signalCancellation();
        update("CANCELLED", progress(), null);
        data.put("endTime", Long.valueOf(System.currentTimeMillis()));
        persist();
    }

    synchronized boolean prepareResume(String sessionId) {
        String status = status();
        if ("RUNNING".equals(status)) return false;
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status))
            throw new IllegalArgumentException("当前任务状态不支持恢复: " + status);
        signalCancellation();
        clearAttempt();
        data.put("sessionId", sessionId);
        data.put("status", "PENDING");
        data.put("error", null);
        data.put("endTime", null);
        if ("TABLE_EXPORT".equals(type())) {
            data.put("progress", Integer.valueOf(0));
            data.put("rowCount", null);
            data.put("fileSize", null);
        }
        return true;
    }

    synchronized void cancelOnShutdown() {
        if (!isTerminalStatus(status())) update("CANCELLED", progress(), null);
    }

    synchronized boolean isExpired(long now, long retention) {
        long endTime = toLong(data.get("endTime"));
        return isTerminalStatus(status()) && endTime > 0L && now - endTime > retention;
    }

    synchronized boolean checkStopped(Attempt current) {
        if (current == null) return false;
        if (!isCurrent(current)) return true;
        if (current.cancelRequested) {
            update("CANCELLED", progress(), null);
            data.put("endTime", Long.valueOf(System.currentTimeMillis()));
            return true;
        }
        if (current.pauseRequested) {
            update("PAUSED", progress(), null);
            return true;
        }
        return false;
    }

    /** The table writer has already checkpointed its stop; this final check is read-only. */
    synchronized boolean tableStopped(Attempt current) {
        return !isCurrent(current) || current.cancelRequested || "CANCELLED".equals(status())
                || current.pauseRequested || "PAUSED".equals(status());
    }

    synchronized void running(Attempt current, int percent) {
        if (isCurrent(current)) update("RUNNING", percent, null);
    }

    synchronized void rowsWritten(Attempt current, long rows) {
        if (isCurrent(current)) data.put("rowCount", rows);
    }

    synchronized void tablesResolved(Attempt current, int count) {
        if (isCurrent(current)) data.put("tableCount", Integer.valueOf(count));
    }

    synchronized void tableStarted(Attempt current, SqlObjectRef table, int index, int percent) {
        if (!isCurrent(current)) return;
        data.put("currentTable", table.name());
        data.put("currentObjectRef", table.toMap());
        data.put("processedTables", Integer.valueOf(index));
        running(current, percent);
    }

    synchronized void tableCompleted(Attempt current, int count) {
        if (!isCurrent(current)) return;
        data.put("processedTables", Integer.valueOf(count));
        persist();
    }

    synchronized void complete(Attempt current, long fileSize) {
        if (!isCurrent(current)) return;
        data.put("fileSize", Long.valueOf(fileSize));
        update("COMPLETED", 100, null);
    }

    synchronized void completeDatabase(Attempt current, int tables, long fileSize) {
        if (!isCurrent(current)) return;
        data.put("processedTables", Integer.valueOf(tables));
        complete(current, fileSize);
    }

    synchronized void fail(Attempt current, String error) {
        if (isCurrent(current)) update("FAILED", progress(), error);
    }

    synchronized void failUnlessStopped(Attempt current, Exception error) {
        if (!isCurrent(current)) return;
        if (!"PAUSED".equals(status()) && !"CANCELLED".equals(status())) fail(current, error.getMessage());
    }

    private String status() { return safeString(data.get("status")); }
    private int progress() { return toInt(data.get("progress")); }

    private void update(String status, int progress, String error) {
        // Late callbacks must not revive a cancelled task.
        if ("CANCELLED".equals(data.get("status")) && !"CANCELLED".equals(status)) return;
        data.put("status", status);
        data.put("progress", Integer.valueOf(Math.max(0, Math.min(100, progress))));
        if ("RUNNING".equals(status) && data.get("startTime") == null)
            data.put("startTime", Long.valueOf(System.currentTimeMillis()));
        if (isTerminalStatus(status)) data.put("endTime", Long.valueOf(System.currentTimeMillis()));
        if (error != null) data.put("error", error);
        persist();
    }

    synchronized void persist() {
        try { repository.save(key(), copy(data)); }
        catch (Exception ignored) { }
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, copyValue(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(copyValue(item));
            return result;
        }
        return value;
    }

    static final class Attempt {
        private boolean pauseRequested;
        private boolean cancelRequested;
    }

    private static Map<String, Object> createTask(String taskId,
                                           String userId,
                                           String sessionId,
                                           String taskType,
                                           String fileName,
                                           Path finalPath,
                                           String database,
                                           String table, String downloadPath) {
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("taskId", taskId);
        task.put("userId", userId);
        task.put("sessionId", sessionId);
        task.put("taskType", taskType);
        task.put("status", "PENDING");
        task.put("progress", Integer.valueOf(0));
        task.put("fileName", fileName);
        task.put("database", database);
        task.put("currentTable", table);
        task.put("createdTime", Long.valueOf(System.currentTimeMillis()));
        task.put("startTime", null);
        task.put("endTime", null);
        task.put("error", null);
        task.put("downloadPath", downloadPath);
        task.put("downloadUrl", buildDownloadUrl(downloadPath, fileName));
        task.put("finalPath", finalPath.toAbsolutePath().toString());
        task.put("processedTables", Integer.valueOf(0));
        return task;
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("taskId", this.data.get("taskId"));
        data.put("taskType", this.data.get("taskType"));
        data.put("status", this.data.get("status"));
        data.put("progress", this.data.get("progress"));
        data.put("fileName", this.data.get("fileName"));
        data.put("database", this.data.get("database"));
        data.put("currentTable", this.data.get("currentTable"));
        data.put("processedTables", this.data.get("processedTables"));
        data.put("tableCount", this.data.get("tableCount"));
        data.put("rowCount", this.data.get("rowCount"));
        data.put("fileSize", this.data.get("fileSize"));
        data.put("error", this.data.get("error"));
        data.put("createdTime", this.data.get("createdTime"));
        data.put("startTime", this.data.get("startTime"));
        data.put("endTime", this.data.get("endTime"));
        data.put("downloadPath", this.data.get("downloadPath"));
        data.put("downloadUrl", this.data.get("downloadUrl"));
        data.put("format", this.data.get("format"));
        data.put("includeStructure", this.data.get("includeStructure"));
        data.put("includeData", this.data.get("includeData"));
        data.put("objectRef", this.data.get("objectRef"));
        data.put("currentObjectRef", this.data.get("currentObjectRef"));
        data.put("tableRefs", this.data.get("tableRefs"));
        return copy(data);
    }

    static List<Map<String, Object>> toRefMaps(List<SqlObjectRef> refs) {
        if (refs == null || refs.isEmpty()) return Collections.emptyList();
        return refs.stream().filter(Objects::nonNull).map(SqlObjectRef::toMap).toList();
    }

    static SqlObjectRef refValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return SqlObjectRef.fromMap(map);
    }

    static List<SqlObjectRef> refListValue(Object value) {
        if (!(value instanceof List<?> list)) return Collections.emptyList();
        List<SqlObjectRef> refs = new ArrayList<SqlObjectRef>();
        for (Object item : list) {
            SqlObjectRef ref = refValue(item);
            if (ref != null) refs.add(ref);
        }
        return refs;
    }

    static String exportObjectName(SqlObjectRef ref) {
        if (ref == null) return "default";
        List<String> parts = new ArrayList<String>();
        if (!isBlank(ref.catalog())) parts.add(ref.catalog());
        if (!isBlank(ref.schema())) parts.add(ref.schema());
        if (!isBlank(ref.name())) parts.add(ref.name());
        return parts.isEmpty() ? "default" : String.join("_", parts);
    }

    private static String buildDownloadUrl(String relativePath, String fileName) {
        return "/user/file/download?path=" + urlEncode(relativePath) + "&filename=" + urlEncode(fileName);
    }

    private static String urlEncode(String value) {
        return value == null ? null : java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Export options default to enabled when omitted or blank. */
    private static boolean toBoolean(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return true;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text);
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
