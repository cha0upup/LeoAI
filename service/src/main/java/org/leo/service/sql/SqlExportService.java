package org.leo.service.sql;

import jakarta.annotation.PreDestroy;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates task lifecycle; storage and CSV content generation are separate collaborators. */
@Service
public class SqlExportService {

    private static final long FINISHED_TASK_RETAIN_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<String, Map<String, Object>> liveTasks = new ConcurrentHashMap<String, Map<String, Object>>();
    private final ConcurrentHashMap<String, TaskControl> liveControls = new ConcurrentHashMap<String, TaskControl>();
    private final AgentNodeSqlService agentNodeSqlService;
    private final ServiceTaskExecutor taskExecutor;
    private final SqlExportTaskRepository taskRepository;
    private final SqlExportArtifacts artifacts;
    private final SqlTableCsvExporter csvExporter;

    public SqlExportService(AgentNodeSqlService agentNodeSqlService,
                            ServiceTaskExecutor taskExecutor,
                            SqlExportTaskRepository taskRepository,
                            SqlExportArtifacts artifacts,
                            SqlTableCsvExporter csvExporter) {
        this.agentNodeSqlService = agentNodeSqlService;
        this.taskExecutor = taskExecutor;
        this.taskRepository = taskRepository;
        this.artifacts = artifacts;
        this.csvExporter = csvExporter;
    }

    public Map<String, Object> startTableExport(SqlCapable agentNode,
                                                String userId,
                                                String sessionId,
                                                Map<String, Object> connection,
                                                SqlObjectRef tableRef,
                                                String format) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (tableRef == null || isBlank(tableRef.name())) {
            throw new IllegalArgumentException("objectRef 必须包含表名");
        }
        String exportFormat = safeLower(format);
        if (!"csv".equals(exportFormat)) {
            throw new IllegalArgumentException("当前仅支持 csv 格式单表导出");
        }

        String taskId = "sql_export_" + UUID.randomUUID().toString().replace("-", "");
        String namespace = tableRef.namespace();
        String fileName = sanitizeFileName(exportObjectName(tableRef) + "_" + System.currentTimeMillis() + ".csv");
        Path finalPath = artifacts.reserveOutput(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "TABLE_EXPORT",
                finalPath.getFileName().toString(), finalPath, namespace, tableRef.name());
        task.put("objectRef", tableRef.toMap());
        task.put("format", exportFormat);
        scheduleTask(task, agentNode, connection);

        return publicTask(task);
    }

    public Map<String, Object> startDatabaseExport(SqlCapable agentNode,
                                                   String userId,
                                                   String sessionId,
                                                   Map<String, Object> connection,
                                                   SqlObjectRef namespaceRef,
                                                   List<SqlObjectRef> tableRefs,
                                                   Boolean includeStructure,
                                                   Boolean includeData,
                                                   String format) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (namespaceRef == null || isBlank(namespaceRef.namespace())) {
            throw new IllegalArgumentException("objectRef 必须包含 catalog 或 schema");
        }
        String exportFormat = safeLower(format);
        if (!"zip".equals(exportFormat)) {
            throw new IllegalArgumentException("当前仅支持 zip 格式整库导出");
        }
        boolean exportStructure = includeStructure == null || includeStructure.booleanValue();
        boolean exportData = includeData == null || includeData.booleanValue();
        if (!exportStructure && !exportData) {
            throw new IllegalArgumentException("includeStructure 和 includeData 不能同时为 false");
        }

        String taskId = "sql_export_" + UUID.randomUUID().toString().replace("-", "");
        String namespace = namespaceRef.namespace();
        String fileName = sanitizeFileName(exportObjectName(namespaceRef) + "_" + System.currentTimeMillis() + ".zip");
        Path finalPath = artifacts.reserveOutput(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "DATABASE_EXPORT",
                finalPath.getFileName().toString(), finalPath, namespace, null);
        task.put("format", exportFormat);
        task.put("includeStructure", exportStructure);
        task.put("includeData", exportData);
        task.put("objectRef", namespaceRef.toMap());
        task.put("tableRefs", toRefMaps(tableRefs));
        scheduleTask(task, agentNode, connection);

        return publicTask(task);
    }

    public Map<String, Object> progress(String userId, String taskId) throws Exception {
        return publicTask(loadOwnedTask(userId, taskId));
    }

    public Map<String, Object> pause(String userId, String taskId) throws Exception {
        Map<String, Object> task = requireTask(userId, taskId);
        if (isTerminalStatus(safeString(task.get("status")))) {
            return publicTask(task);
        }
        TaskControl control = liveControls.get(taskId);
        if (control == null) {
            task.put("status", "PAUSED");
            persistTask(task);
            return publicTask(task);
        }
        control.pauseRequested.set(true);
        return publicTask(task);
    }

    public Map<String, Object> stop(String userId, String taskId) throws Exception {
        Map<String, Object> task = requireTask(userId, taskId);
        if (isTerminalStatus(safeString(task.get("status")))) {
            return publicTask(task);
        }
        TaskControl control = liveControls.get(taskId);
        if (control != null) {
            control.cancelRequested.set(true);
            control.pauseRequested.set(false);
        }
        updateTask(task, "CANCELLED", toInt(task.get("progress")), null);
        task.put("endTime", Long.valueOf(System.currentTimeMillis()));
        persistTask(task);
        return publicTask(task);
    }

    public Map<String, Object> resume(SqlCapable agentNode,
                                      String userId,
                                      String sessionId,
                                      String taskId,
                                      Map<String, Object> connection) throws Exception {
        Map<String, Object> task = requireTask(userId, taskId);
        String status = safeString(task.get("status"));
        if ("RUNNING".equals(status)) {
            return publicTask(task);
        }
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            throw new IllegalArgumentException("当前任务状态不支持恢复: " + status);
        }
        task.put("sessionId", sessionId);
        task.put("status", "PENDING");
        task.put("error", null);
        task.put("endTime", null);
        if ("TABLE_EXPORT".equals(String.valueOf(task.get("taskType")))) {
            task.put("progress", Integer.valueOf(0));
            task.put("rowCount", null);
            task.put("fileSize", null);
        }
        scheduleTask(task, agentNode, connection);
        return publicTask(task);
    }

    public Map<String, Object> listBySessionId(String userId, String sessionId) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();
        for (String taskId : taskRepository.listTaskIds(userId)) {
            try {
                Map<String, Object> task = loadOwnedTask(userId, taskId);
                if (sessionId.equals(String.valueOf(task.get("sessionId")))) tasks.add(publicTask(task));
            } catch (Exception ignored) {
                // A missing/corrupt or out-of-scope entry must not expose another task.
            }
        }
        Collections.sort(tasks, (a, b) -> {
            long av = toLong(a.get("createdTime"));
            long bv = toLong(b.get("createdTime"));
            return Long.compare(bv, av);
        });
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", sessionId);
        result.put("count", Integer.valueOf(tasks.size()));
        result.put("tasks", tasks);
        return result;
    }

    private void scheduleTask(final Map<String, Object> task,
                              final SqlCapable agentNode,
                              final Map<String, Object> connection) {
        final String taskId = String.valueOf(task.get("taskId"));
        persistTask(task);
        liveTasks.put(taskId, task);
        final TaskControl newControl = new TaskControl();
        TaskControl old = liveControls.put(taskId, newControl);
        if (old != null) {
            old.cancelRequested.set(true);
        }
        try {
            taskExecutor.submitSqlExport(new Runnable() {
                @Override
                public void run() {
                    if (liveControls.get(taskId) != newControl) return;
                    String type = String.valueOf(task.get("taskType"));
                    try {
                        Path finalPath = artifacts.restoreOutput(String.valueOf(task.get("userId")),
                                String.valueOf(task.get("finalPath")));
                        if ("TABLE_EXPORT".equals(type)) {
                            runTableExport(taskId, agentNode, connection,
                                    refValue(task.get("objectRef")),
                                    finalPath,
                                    newControl);
                        } else if ("DATABASE_EXPORT".equals(type)) {
                            runDatabaseExport(taskId, agentNode, connection,
                                    refValue(task.get("objectRef")),
                                    refListValue(task.get("tableRefs")),
                                    toBoolean(task.get("includeStructure")),
                                    toBoolean(task.get("includeData")),
                                    finalPath,
                                    newControl);
                        }
                    } catch (Exception error) {
                        updateTask(task, "FAILED", toInt(task.get("progress")), error.getMessage());
                    } finally {
                        liveControls.remove(taskId, newControl);
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            liveControls.remove(taskId, newControl);
            updateTask(task, "FAILED", toInt(task.get("progress")), "SQL 导出任务队列繁忙");
        }
    }

    public int evictFinished() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        for (Map.Entry<String, Map<String, Object>> entry : liveTasks.entrySet()) {
            Map<String, Object> task = entry.getValue();
            String status = safeString(task.get("status"));
            long endTime = toLong(task.get("endTime"));
            if (isTerminalStatus(status) && endTime > 0L
                    && now - endTime > FINISHED_TASK_RETAIN_MILLIS
                    && liveTasks.remove(entry.getKey(), task)) {
                liveControls.remove(entry.getKey());
                evicted++;
            }
        }
        return evicted;
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    @PreDestroy
    public void close() {
        for (TaskControl control : liveControls.values()) {
            control.cancelRequested.set(true);
            control.pauseRequested.set(false);
        }
        for (Map<String, Object> task : liveTasks.values()) {
            if (!isTerminalStatus(safeString(task.get("status")))) {
                updateTask(task, "CANCELLED", toInt(task.get("progress")), null);
            }
        }
        liveControls.clear();
    }

    private void runTableExport(String taskId,
                                SqlCapable agentNode,
                                Map<String, Object> connection,
                                SqlObjectRef tableRef,
                                Path finalPath,
                                TaskControl control) {
        Map<String, Object> task = liveTasks.get(taskId);
        if (task == null) {
            return;
        }
        try {
            if (checkPausedOrCancelled(task, control)) return;
            updateTask(task, "RUNNING", 1, null);
            Map<String, Object> columnResult = agentNodeSqlService.getTableColumns(
                    agentNode, connection, tableRef);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) columnResult.get("columns");

            try (BufferedWriter writer = artifacts.openCsv(finalPath)) {
                long total = csvExporter.write(writer, agentNode, connection, tableRef, columns,
                        progress(task, 10, 95, control));
                task.put("rowCount", total);
            }
            if (control.cancelRequested.get() || "CANCELLED".equals(safeString(task.get("status")))) {
                return;
            }
            if (control.pauseRequested.get() || "PAUSED".equals(safeString(task.get("status")))) {
                return;
            }
            task.put("fileSize", Long.valueOf(artifacts.size(finalPath)));
            updateTask(task, "COMPLETED", 100, null);
        } catch (Exception e) {
            failExportUnlessStopped(task, e);
        } finally {
            persistTask(task);
        }
    }

    private void runDatabaseExport(String taskId,
                                   SqlCapable agentNode,
                                   Map<String, Object> connection,
                                   SqlObjectRef namespaceRef,
                                   List<SqlObjectRef> selectedTableRefs,
                                   boolean includeStructure,
                                   boolean includeData,
                                   Path finalPath,
                                   TaskControl control) {
        Map<String, Object> task = liveTasks.get(taskId);
        if (task == null) {
            return;
        }
        Path workDir = artifacts.workDirectory(new SqlExportTaskKey(String.valueOf(task.get("userId")), taskId));
        try {
            if (checkPausedOrCancelled(task, control)) return;
            updateTask(task, "RUNNING", 1, null);
            List<SqlObjectRef> tables = resolveExportTableRefs(
                    agentNode, connection, namespaceRef, selectedTableRefs);
            task.put("tableCount", Integer.valueOf(tables.size()));

            int startIndex = Math.max(0, toInt(task.get("processedTables")));
            for (int i = startIndex; i < tables.size(); i++) {
                if (checkPausedOrCancelled(task, control)) {
                    return;
                }
                SqlObjectRef tableRef = tables.get(i);
                String table = tableRef.name();
                String entryName = exportObjectName(tableRef);
                task.put("currentTable", table);
                task.put("currentObjectRef", tableRef.toMap());
                task.put("processedTables", Integer.valueOf(i));
                int progressBase = tables.isEmpty() ? 90 : (int) Math.min(90, 5 + ((double) i / (double) tables.size()) * 85);
                updateTask(task, "RUNNING", progressBase, null);

                Map<String, Object> columnResult = agentNodeSqlService.getTableColumns(
                        agentNode, connection, tableRef);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns =
                        (List<Map<String, Object>>) columnResult.get("columns");
                if (includeStructure) {
                    artifacts.writeJson(workDir.resolve("structure").resolve(
                            sanitizeFileName(entryName) + ".columns.json"), columnResult);
                }
                if (includeData) {
                    Path csvPath = workDir.resolve("data").resolve(sanitizeFileName(entryName) + ".csv");
                    try (BufferedWriter writer = artifacts.openCsv(csvPath)) {
                        csvExporter.write(writer, agentNode, connection, tableRef, columns,
                                progress(task, progressBase, 95, control));
                    }
                }
                if (checkPausedOrCancelled(task, control)) {
                    return;
                }
                task.put("processedTables", Integer.valueOf(i + 1));
                persistTask(task);
            }

            Map<String, Object> manifest = new LinkedHashMap<String, Object>();
            manifest.put("objectRef", namespaceRef.toMap());
            manifest.put("tableCount", Integer.valueOf(tables.size()));
            manifest.put("includeStructure", Boolean.valueOf(includeStructure));
            manifest.put("includeData", Boolean.valueOf(includeData));
            manifest.put("exportTime", Instant.now().toString());
            manifest.put("tables", tables.stream().map(SqlObjectRef::toMap).toList());
            artifacts.writeJson(workDir.resolve("manifest.json"), manifest);

            artifacts.zipDirectory(workDir, finalPath);
            if (checkPausedOrCancelled(task, control)) {
                return;
            }
            task.put("processedTables", Integer.valueOf(tables.size()));
            task.put("fileSize", Long.valueOf(artifacts.size(finalPath)));
            updateTask(task, "COMPLETED", 100, null);
        } catch (Exception e) {
            failExportUnlessStopped(task, e);
        } finally {
            persistTask(task);
        }
    }

    private void failExportUnlessStopped(Map<String, Object> task, Exception error) {
        if ("PAUSED".equals(safeString(task.get("status"))) || "CANCELLED".equals(safeString(task.get("status")))) {
            return;
        }
        updateTask(task, "FAILED", toInt(task.get("progress")), error.getMessage());
    }

    private SqlTableCsvExporter.Progress progress(Map<String, Object> task, int start, int end, TaskControl control) {
        return new SqlTableCsvExporter.Progress() {
            @Override public boolean stopped() { return checkPausedOrCancelled(task, control); }
            @Override public void rowsWritten(long rows, long expectedTotal) {
                if (checkPausedOrCancelled(task, control)) return;
                int percent = start + (int) Math.min(end - start, (rows * (end - start)) / Math.max(1L, expectedTotal));
                updateTask(task, "RUNNING", percent, null);
            }
        };
    }

    private boolean checkPausedOrCancelled(Map<String, Object> task, TaskControl control) {
        if (control == null) {
            return false;
        }
        if (control.cancelRequested.get()) {
            updateTask(task, "CANCELLED", toInt(task.get("progress")), null);
            task.put("endTime", Long.valueOf(System.currentTimeMillis()));
            return true;
        }
        if (control.pauseRequested.get()) {
            updateTask(task, "PAUSED", toInt(task.get("progress")), null);
            return true;
        }
        return false;
    }

    private List<SqlObjectRef> resolveExportTableRefs(SqlCapable agentNode,
                                                      Map<String, Object> connection,
                                                      SqlObjectRef namespaceRef,
                                                      List<SqlObjectRef> selectedTableRefs) throws Exception {
        if (selectedTableRefs != null && !selectedTableRefs.isEmpty()) {
            return selectedTableRefs.stream()
                    .filter(ref -> ref != null && !isBlank(ref.name()))
                    .distinct()
                    .sorted(Comparator.comparing(this::refSortKey)).toList();
        }
        Map<String, Object> result = agentNodeSqlService.getTables(
                agentNode, connection, namespaceRef);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        List<SqlObjectRef> refs = new ArrayList<SqlObjectRef>();
        if (tables != null) {
            for (Map<String, Object> item : tables) {
                SqlObjectRef ref = refValue(item.get("ref"));
                if (ref != null) refs.add(ref);
            }
        }
        refs.sort(Comparator.comparing(this::refSortKey));
        return refs;
    }

    private Map<String, Object> createTask(String taskId,
                                           String userId,
                                           String sessionId,
                                           String taskType,
                                           String fileName,
                                           Path finalPath,
                                           String database,
                                           String table) {
        String downloadPath = artifacts.relativePath(userId, finalPath);
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

    private String refSortKey(SqlObjectRef ref) {
        if (ref == null) return "";
        return safeString(ref.catalog()) + "\u0000" + safeString(ref.schema())
                + "\u0000" + safeString(ref.name());
    }

    private void updateTask(Map<String, Object> task, String status, int progress, String error) {
        synchronized (task) {
            // A late progress/completion callback must not revive a cancelled task.
            if ("CANCELLED".equals(task.get("status")) && !"CANCELLED".equals(status)) return;
            applyTaskUpdate(task, status, progress, error);
        }
    }

    private void applyTaskUpdate(Map<String, Object> task, String status, int progress, String error) {
        task.put("status", status);
        task.put("progress", Integer.valueOf(Math.max(0, Math.min(100, progress))));
        if ("RUNNING".equals(status) && task.get("startTime") == null) {
            task.put("startTime", Long.valueOf(System.currentTimeMillis()));
        }
        if (isTerminalStatus(status)) {
            task.put("endTime", Long.valueOf(System.currentTimeMillis()));
        }
        if (error != null) {
            task.put("error", error);
        }
        persistTask(task);
    }

    private void persistTask(Map<String, Object> task) {
        if (task == null) {
            return;
        }
        try {
            String userId = String.valueOf(task.get("userId"));
            String taskId = String.valueOf(task.get("taskId"));
            taskRepository.save(new SqlExportTaskKey(userId, taskId), task);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> publicTask(Map<String, Object> task) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("taskId", task.get("taskId"));
        data.put("taskType", task.get("taskType"));
        data.put("status", task.get("status"));
        data.put("progress", task.get("progress"));
        data.put("fileName", task.get("fileName"));
        data.put("database", task.get("database"));
        data.put("currentTable", task.get("currentTable"));
        data.put("processedTables", task.get("processedTables"));
        data.put("tableCount", task.get("tableCount"));
        data.put("rowCount", task.get("rowCount"));
        data.put("fileSize", task.get("fileSize"));
        data.put("error", task.get("error"));
        data.put("createdTime", task.get("createdTime"));
        data.put("startTime", task.get("startTime"));
        data.put("endTime", task.get("endTime"));
        data.put("downloadPath", task.get("downloadPath"));
        data.put("downloadUrl", task.get("downloadUrl"));
        data.put("format", task.get("format"));
        data.put("includeStructure", task.get("includeStructure"));
        data.put("includeData", task.get("includeData"));
        data.put("objectRef", task.get("objectRef"));
        data.put("currentObjectRef", task.get("currentObjectRef"));
        data.put("tableRefs", task.get("tableRefs"));
        return data;
    }

    private Map<String, Object> requireTask(String userId, String taskId) throws Exception {
        Map<String, Object> task = loadOwnedTask(userId, taskId);
        Map<String, Object> previous = liveTasks.putIfAbsent(taskId, task);
        return new SqlExportTaskKey(userId, taskId).requireOwner(previous == null ? task : previous);
    }

    private Map<String, Object> loadOwnedTask(String userId, String taskId) throws Exception {
        // Validate before cache lookup, then verify the owner even when a shared cache entry exists.
        SqlExportTaskKey key = new SqlExportTaskKey(userId, taskId);
        Map<String, Object> live = liveTasks.get(taskId);
        return key.requireOwner(live == null ? taskRepository.load(key) : live);
    }

    private String buildDownloadUrl(String relativePath, String fileName) {
        return "/user/file/download?path=" + urlEncode(relativePath) + "&filename=" + urlEncode(fileName);
    }

    private String urlEncode(String value) {
        return value == null ? null : java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "export.dat";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_");
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private long toLong(Object value) {
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
    private boolean toBoolean(Object value) {
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

    private static final class TaskControl {
        private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    }
}
