package org.leo.service.sql;

import jakarta.annotation.PreDestroy;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.leo.service.sql.SqlExportTask.exportObjectName;
import static org.leo.service.sql.SqlExportTask.refValue;

/** Coordinates export use cases and scheduling; each task owns its state and execution attempt. */
@Service
public class SqlExportService {

    public static final String TABLE_FORMAT = "xlsx";
    public static final String DATABASE_FORMAT = "zip";
    private static final long FINISHED_TASK_RETAIN_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<SqlExportTaskKey, SqlExportTask> liveTasks = new ConcurrentHashMap<>();
    private final AgentNodeSqlService agentNodeSqlService;
    private final ServiceTaskExecutor taskExecutor;
    private final SqlExportTaskRepository taskRepository;
    private final SqlExportArtifacts artifacts;
    private final SqlTableExporter tableExporter;

    public SqlExportService(AgentNodeSqlService agentNodeSqlService,
                            ServiceTaskExecutor taskExecutor,
                            SqlExportTaskRepository taskRepository,
                            SqlExportArtifacts artifacts,
                            SqlTableExporter tableExporter) {
        this.agentNodeSqlService = agentNodeSqlService;
        this.taskExecutor = taskExecutor;
        this.taskRepository = taskRepository;
        this.artifacts = artifacts;
        this.tableExporter = tableExporter;
    }

    public Map<String, Object> startTableExport(SqlCapable agentNode,
                                                String userId,
                                                String sessionId,
                                                Map<String, Object> connection,
                                                SqlObjectRef tableRef) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (tableRef == null || isBlank(tableRef.name())) {
            throw new IllegalArgumentException("objectRef 必须包含表名");
        }
        SqlExportTaskKey key = newTaskKey(userId);
        String fileName = sanitizeFileName(exportObjectName(tableRef) + "_" + System.currentTimeMillis() + "." + TABLE_FORMAT);
        Path finalPath = artifacts.reserveOutput(key.userId(), fileName);

        SqlExportTask task = SqlExportTask.table(taskRepository, key, sessionId,
                finalPath, artifacts.relativePath(key.userId(), finalPath), tableRef);
        return startTask(task, agentNode, connection);
    }

    public Map<String, Object> startDatabaseExport(SqlCapable agentNode,
                                                   String userId,
                                                   String sessionId,
                                                   Map<String, Object> connection,
                                                   SqlObjectRef namespaceRef,
                                                   List<SqlObjectRef> tableRefs,
                                                   Boolean includeStructure,
                                                   Boolean includeData) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (namespaceRef == null || isBlank(namespaceRef.namespace())) {
            throw new IllegalArgumentException("objectRef 必须包含 catalog 或 schema");
        }
        boolean exportStructure = includeStructure == null || includeStructure.booleanValue();
        boolean exportData = includeData == null || includeData.booleanValue();
        if (!exportStructure && !exportData) {
            throw new IllegalArgumentException("includeStructure 和 includeData 不能同时为 false");
        }

        SqlExportTaskKey key = newTaskKey(userId);
        String fileName = sanitizeFileName(exportObjectName(namespaceRef) + "_" + System.currentTimeMillis() + "." + DATABASE_FORMAT);
        Path finalPath = artifacts.reserveOutput(key.userId(), fileName);

        SqlExportTask task = SqlExportTask.database(taskRepository, key, sessionId,
                finalPath, artifacts.relativePath(key.userId(), finalPath), namespaceRef, tableRefs,
                exportStructure, exportData);
        return startTask(task, agentNode, connection);
    }

    public Map<String, Object> progress(String userId, String taskId) throws Exception {
        SqlExportTaskKey key = new SqlExportTaskKey(userId, taskId);
        SqlExportTask live = liveTasks.get(key);
        return live == null ? SqlExportTask.snapshot(loadOwnedMetadata(key)) : live.snapshot();
    }

    public Map<String, Object> pause(String userId, String taskId) throws Exception {
        return updateTask(userId, taskId, SqlExportTask::pause);
    }

    public Map<String, Object> stop(String userId, String taskId) throws Exception {
        return updateTask(userId, taskId, SqlExportTask::cancel);
    }

    public Map<String, Object> resume(SqlCapable agentNode,
                                      String userId, String sessionId, String taskId,
                                      Map<String, Object> connection) throws Exception {
        return updateTask(userId, taskId, task -> {
            if (task.prepareResume(sessionId)) scheduleTask(task, agentNode, connection);
        });
    }

    public Map<String, Object> listBySessionId(String userId, String sessionId) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        List<SqlExportTask.ListedTask> tasks = new ArrayList<>();
        for (String taskId : taskRepository.listTaskIds(userId)) {
            try {
                SqlExportTaskKey key = new SqlExportTaskKey(userId, taskId);
                SqlExportTask live = liveTasks.get(key);
                SqlExportTask.ListedTask row = live == null
                        ? SqlExportTask.snapshotForSession(loadOwnedMetadata(key), sessionId)
                        : live.snapshotForSession(sessionId);
                if (row != null) tasks.add(row);
            } catch (Exception ignored) {
                // A missing/corrupt or out-of-scope entry must not expose another task.
            }
        }
        tasks.sort(Comparator.comparingLong(SqlExportTask.ListedTask::createdTime).reversed());
        List<Map<String, Object>> snapshots = new ArrayList<>(tasks.size());
        for (SqlExportTask.ListedTask task : tasks) snapshots.add(task.snapshot());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", sessionId);
        result.put("count", Integer.valueOf(tasks.size()));
        result.put("tasks", snapshots);
        return result;
    }

    private Map<String, Object> startTask(SqlExportTask task, SqlCapable agentNode, Map<String, Object> connection) {
        synchronized (task) {
            liveTasks.put(task.key(), task);
            scheduleTask(task, agentNode, connection);
        }
        return task.snapshot();
    }

    /** Called with the registered task locked, so its transition and scheduling stay together. */
    private void scheduleTask(SqlExportTask task, SqlCapable agentNode, Map<String, Object> connection) {
        task.persist();
        SqlExportTask.Attempt attempt = task.beginAttempt();
        try {
            taskExecutor.submitSqlExport(() -> runTask(task, agentNode, connection, attempt));
        } catch (RejectedExecutionException error) {
            task.fail(attempt, "SQL 导出任务队列繁忙");
            task.finishAttempt(attempt);
        }
    }

    private void runTask(SqlExportTask task, SqlCapable agentNode, Map<String, Object> connection,
                         SqlExportTask.Attempt attempt) {
        if (liveTasks.get(task.key()) != task || !task.isCurrent(attempt)) return;
        try {
            if (task.checkStopped(attempt)) return;
            Path finalPath = artifacts.restoreOutput(task.key().userId(), task.finalPath());
            if (task.checkStopped(attempt)) return;
            task.running(attempt, 1);
            switch (task.type()) {
                case "TABLE_EXPORT" -> runTableExport(task, agentNode, connection, finalPath, attempt);
                case "DATABASE_EXPORT" -> runDatabaseExport(task, agentNode, connection, finalPath, attempt);
                default -> throw new IllegalArgumentException("未知 SQL 导出任务类型: " + task.type());
            }
        } catch (Exception error) {
            task.failUnlessStopped(attempt, error);
        } finally {
            task.finishAttempt(attempt);
        }
    }

    public int evictFinished() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        for (Map.Entry<SqlExportTaskKey, SqlExportTask> entry : liveTasks.entrySet()) {
            SqlExportTask task = entry.getValue();
            synchronized (task) {
                if (task.isExpired(now, FINISHED_TASK_RETAIN_MILLIS)
                        && liveTasks.remove(entry.getKey(), task)) {
                    task.clearAttempt();
                    evicted++;
                }
            }
        }
        return evicted;
    }

    @PreDestroy
    public void close() {
        liveTasks.values().forEach(SqlExportTask::signalCancellation);
        liveTasks.values().forEach(SqlExportTask::cancelOnShutdown);
        liveTasks.values().forEach(SqlExportTask::clearAttempt);
    }

    private void runTableExport(SqlExportTask task,
                                SqlCapable agentNode,
                                Map<String, Object> connection,
                                Path finalPath,
                                SqlExportTask.Attempt attempt) throws Exception {
        SqlObjectRef tableRef = task.objectRef();
        Map<String, Object> columnResult = agentNodeSqlService.getTableColumns(
                agentNode, connection, tableRef);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) columnResult.get("columns");

        long total = writeTableWorkbook(finalPath, agentNode, connection, tableRef, columns,
                progress(task, 10, 95, attempt));
        task.rowsWritten(attempt, total);
        if (task.tableStopped(attempt)) return;
        task.complete(attempt, artifacts.size(finalPath));
    }

    private void runDatabaseExport(SqlExportTask task,
                                   SqlCapable agentNode,
                                   Map<String, Object> connection,
                                   Path finalPath,
                                   SqlExportTask.Attempt attempt) throws Exception {
        SqlObjectRef namespaceRef = task.objectRef();
        boolean includeStructure = task.includeStructure();
        boolean includeData = task.includeData();
        Path workDir = artifacts.workDirectory(task.key());
        if (task.checkStopped(attempt)) return;
        List<SqlObjectRef> tables = resolveExportTableRefs(
                agentNode, connection, namespaceRef, task.selectedTables());
        task.tablesResolved(attempt, tables.size());

        int startIndex = Math.max(0, task.processedTables());
        for (int i = startIndex; i < tables.size(); i++) {
            if (task.checkStopped(attempt)) return;
            SqlObjectRef tableRef = tables.get(i);
            String entryName = exportObjectName(tableRef);
            int progressBase = (int) Math.min(90, 5 + ((double) i / (double) tables.size()) * 85);
            task.tableStarted(attempt, tableRef, i, progressBase);

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
                Path workbookPath = workDir.resolve("data").resolve(sanitizeFileName(entryName) + "." + TABLE_FORMAT);
                writeTableWorkbook(workbookPath, agentNode, connection, tableRef, columns,
                        progress(task, progressBase, 95, attempt));
            }
            if (task.checkStopped(attempt)) return;
            task.tableCompleted(attempt, i + 1);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("objectRef", namespaceRef.toMap());
        manifest.put("tableCount", tables.size());
        manifest.put("includeStructure", includeStructure);
        manifest.put("includeData", includeData);
        if (includeData) manifest.put("dataFormat", TABLE_FORMAT);
        manifest.put("exportTime", Instant.now().toString());
        manifest.put("tables", tables.stream().map(SqlObjectRef::toMap).toList());
        artifacts.writeJson(workDir.resolve("manifest.json"), manifest);

        artifacts.zipDirectory(workDir, finalPath);
        if (task.checkStopped(attempt)) return;
        task.completeDatabase(attempt, tables.size(), artifacts.size(finalPath));
    }

    private long writeTableWorkbook(Path path, SqlCapable agentNode, Map<String, Object> connection,
                                    SqlObjectRef tableRef, List<Map<String, Object>> columns,
                                    SqlTableExporter.Progress progress) throws Exception {
        try (OutputStream output = artifacts.openWorkbook(path)) {
            return tableExporter.writeWorkbook(output, agentNode, connection, tableRef, columns, progress);
        }
    }

    private SqlTableExporter.Progress progress(SqlExportTask task, int start, int end, SqlExportTask.Attempt attempt) {
        return new SqlTableExporter.Progress() {
            @Override public boolean stopped() { return task.checkStopped(attempt); }
            @Override public void rowsWritten(long rows, long expectedTotal) {
                if (task.checkStopped(attempt)) return;
                int percent = start + (int) Math.min(end - start, (rows * (end - start)) / Math.max(1L, expectedTotal));
                task.reportProgress(attempt, percent);
            }
        };
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

    private String refSortKey(SqlObjectRef ref) {
        if (ref == null) return "";
        return Objects.toString(ref.catalog(), "") + "\u0000" + Objects.toString(ref.schema(), "")
                + "\u0000" + Objects.toString(ref.name(), "");
    }

    private Map<String, Object> updateTask(String userId, String taskId, Consumer<SqlExportTask> action) throws Exception {
        SqlExportTaskKey key = new SqlExportTaskKey(userId, taskId);
        while (true) {
            SqlExportTask task = liveTasks.get(key);
            if (task == null) {
                SqlExportTask restored = SqlExportTask.restore(taskRepository, key);
                SqlExportTask existing = liveTasks.putIfAbsent(key, restored);
                task = existing == null ? restored : existing;
            }
            synchronized (task) {
                // Eviction may have removed this instance while we were waiting for its lock.
                if (liveTasks.get(key) != task) continue;
                action.accept(task);
            }
            return task.snapshot();
        }
    }

    private Map<String, Object> loadOwnedMetadata(SqlExportTaskKey key) throws Exception {
        return key.requireOwner(taskRepository.load(key));
    }

    private static SqlExportTaskKey newTaskKey(String userId) {
        return new SqlExportTaskKey(userId, "sql_export_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "export.dat";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
