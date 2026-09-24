package org.leo.service.sql;

import jakarta.annotation.PreDestroy;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SqlExportService {

    private static final String TASKS_DIR = ".sql-export-tasks";
    private static final String EXPORT_DIR = "downloads/sql-export";
    private static final int PAGE_SIZE = 1000;
    private static final long FINISHED_TASK_RETAIN_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<String, Map<String, Object>> liveTasks = new ConcurrentHashMap<String, Map<String, Object>>();
    private final ConcurrentHashMap<String, TaskControl> liveControls = new ConcurrentHashMap<String, TaskControl>();
    private final PuppetNodeSqlService puppetNodeSqlService;
    private final ServiceTaskExecutor taskExecutor;

    public SqlExportService(PuppetNodeSqlService puppetNodeSqlService,
                            ServiceTaskExecutor taskExecutor) {
        this.puppetNodeSqlService = puppetNodeSqlService;
        this.taskExecutor = taskExecutor;
    }

    public Map<String, Object> startTableExport(SqlCapable puppetNode,
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
        String fileName = sanitizeFileName(exportObjectName(tableRef) + "_" + timestamp() + ".csv");
        Path finalPath = resolveUniqueExportPath(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "TABLE_EXPORT",
                fileName, finalPath, namespace, tableRef.name());
        task.put("objectRef", tableRef.toMap());
        task.put("format", exportFormat);
        task.put("status", "PENDING");
        persistTask(task);
        liveTasks.put(taskId, task);
        scheduleTask(task, puppetNode, connection);

        return publicTask(task);
    }

    public Map<String, Object> startDatabaseExport(SqlCapable puppetNode,
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
        String fileName = sanitizeFileName(exportObjectName(namespaceRef) + "_" + timestamp() + ".zip");
        Path finalPath = resolveUniqueExportPath(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "DATABASE_EXPORT",
                fileName, finalPath, namespace, null);
        task.put("format", exportFormat);
        task.put("status", "PENDING");
        task.put("includeStructure", exportStructure);
        task.put("includeData", exportData);
        task.put("objectRef", namespaceRef.toMap());
        task.put("tableRefs", toRefMaps(tableRefs));
        persistTask(task);
        liveTasks.put(taskId, task);
        scheduleTask(task, puppetNode, connection);

        return publicTask(task);
    }

    public Map<String, Object> getTaskStatus(String userId, String taskId) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        Map<String, Object> task = liveTasks.get(taskId);
        if (task != null) {
            return publicTask(task);
        }
        Path metaFile = taskMetaFile(userId, taskId);
        if (!Files.exists(metaFile)) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) JsonUtil.fromJsonString(new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8), HashMap.class);
        return publicTask(meta);
    }

    public Map<String, Object> progress(String userId, String taskId) throws Exception {
        return getTaskStatus(userId, taskId);
    }

    public Map<String, Object> pause(String userId, String taskId) throws Exception {
        Map<String, Object> task = requireTask(userId, taskId);
        String status = safeString(task.get("status"));
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
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
        String status = safeString(task.get("status"));
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
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

    public Map<String, Object> resume(SqlCapable puppetNode,
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
        persistTask(task);
        liveTasks.put(taskId, task);
        scheduleTask(task, puppetNode, connection);
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
        Path root = userBaseDir(userId).resolve(EXPORT_DIR).resolve(TASKS_DIR);
        if (Files.exists(root)) {
            try (java.util.stream.Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(path -> {
                    Path meta = path.resolve("meta.json");
                    if (!Files.exists(meta)) {
                        return;
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> task = (Map<String, Object>) JsonUtil.fromJsonString(
                                new String(Files.readAllBytes(meta), StandardCharsets.UTF_8), HashMap.class);
                        if (sessionId.equals(String.valueOf(task.get("sessionId")))) {
                            Map<String, Object> live = liveTasks.get(String.valueOf(task.get("taskId")));
                            tasks.add(publicTask(live != null ? live : task));
                        }
                    } catch (Exception ignored) {
                    }
                });
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
                              final SqlCapable puppetNode,
                              final Map<String, Object> connection) {
        final String taskId = String.valueOf(task.get("taskId"));
        final TaskControl newControl = new TaskControl();
        TaskControl old = liveControls.put(taskId, newControl);
        if (old != null) {
            old.cancelRequested.set(true);
        }
        try {
            taskExecutor.submitSqlExport(new Runnable() {
                @Override
                public void run() {
                    String type = String.valueOf(task.get("taskType"));
                    Path finalPath = new File(String.valueOf(task.get("finalPath"))).toPath();
                    try {
                        if ("TABLE_EXPORT".equals(type)) {
                            runTableExport(taskId, puppetNode, connection,
                                    refValue(task.get("objectRef")),
                                    finalPath,
                                    newControl);
                        } else if ("DATABASE_EXPORT".equals(type)) {
                            runDatabaseExport(taskId, puppetNode, connection,
                                    refValue(task.get("objectRef")),
                                    refListValue(task.get("tableRefs")),
                                    toBoolean(task.get("includeStructure"), true),
                                    toBoolean(task.get("includeData"), true),
                                    finalPath,
                                    newControl);
                        }
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
                                SqlCapable puppetNode,
                                Map<String, Object> connection,
                                SqlObjectRef tableRef,
                                Path finalPath,
                                TaskControl control) {
        Map<String, Object> task = liveTasks.get(taskId);
        if (task == null) {
            return;
        }
        try {
            updateTask(task, "RUNNING", 1, null);
            Map<String, Object> columnResult = puppetNodeSqlService.getTableColumns(
                    puppetNode, connection, tableRef);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) columnResult.get("columns");

            Files.createDirectories(finalPath.getParent());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(finalPath.toFile()), StandardCharsets.UTF_8))) {
                List<String> header = extractColumnNames(columns);
                if (!header.isEmpty()) {
                    writer.write(csvLine(header));
                    writer.newLine();
                }
                long total = exportTableDataAsCsv(writer, puppetNode, connection,
                        tableRef, header, task, 10, 95, control);
                task.put("rowCount", total);
            }
            if (control.cancelRequested.get() || "CANCELLED".equals(safeString(task.get("status")))) {
                return;
            }
            if (control.pauseRequested.get() || "PAUSED".equals(safeString(task.get("status")))) {
                return;
            }
            task.put("fileSize", Long.valueOf(Files.size(finalPath)));
            updateTask(task, "COMPLETED", 100, null);
        } catch (Exception e) {
            if ("PAUSED".equals(safeString(task.get("status"))) || "CANCELLED".equals(safeString(task.get("status")))) {
                persistTask(task);
                return;
            }
            updateTask(task, "FAILED", toInt(task.get("progress")), e.getMessage());
        } finally {
            persistTask(task);
        }
    }

    private void runDatabaseExport(String taskId,
                                   SqlCapable puppetNode,
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
        Path workDir = taskWorkDir(String.valueOf(task.get("userId")), taskId);
        try {
            updateTask(task, "RUNNING", 1, null);
            Files.createDirectories(workDir);
            List<SqlObjectRef> tables = resolveExportTableRefs(
                    puppetNode, connection, namespaceRef, selectedTableRefs);
            task.put("tableCount", Integer.valueOf(tables.size()));

            if (includeStructure) {
                Files.createDirectories(workDir.resolve("structure"));
            }
            if (includeData) {
                Files.createDirectories(workDir.resolve("data"));
            }

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

                Map<String, Object> columnResult = puppetNodeSqlService.getTableColumns(
                        puppetNode, connection, tableRef);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns =
                        (List<Map<String, Object>>) columnResult.get("columns");
                if (includeStructure) {
                    writeJson(workDir.resolve("structure").resolve(
                            sanitizeFileName(entryName) + ".columns.json"), columnResult);
                }
                if (includeData) {
                    Path csvPath = workDir.resolve("data").resolve(sanitizeFileName(entryName) + ".csv");
                    try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        List<String> header = extractColumnNames(columns);
                        if (!header.isEmpty()) {
                            writer.write(csvLine(header));
                            writer.newLine();
                        }
                        exportTableDataAsCsv(writer, puppetNode, connection, tableRef,
                                header, task, progressBase, 95, control);
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
            writeJson(workDir.resolve("manifest.json"), manifest);

            Files.createDirectories(finalPath.getParent());
            zipDirectory(workDir, finalPath);
            if (checkPausedOrCancelled(task, control)) {
                return;
            }
            task.put("processedTables", Integer.valueOf(tables.size()));
            task.put("fileSize", Long.valueOf(Files.size(finalPath)));
            updateTask(task, "COMPLETED", 100, null);
        } catch (Exception e) {
            if ("PAUSED".equals(safeString(task.get("status"))) || "CANCELLED".equals(safeString(task.get("status")))) {
                persistTask(task);
                return;
            }
            updateTask(task, "FAILED", toInt(task.get("progress")), e.getMessage());
        } finally {
            persistTask(task);
        }
    }

    private long exportTableDataAsCsv(BufferedWriter writer,
                                      SqlCapable puppetNode,
                                      Map<String, Object> connection,
                                      SqlObjectRef tableRef,
                                      List<String> header,
                                      Map<String, Object> task,
                                      int startProgress,
                                      int endProgress,
                                      TaskControl control) throws Exception {
        long totalRows = 0L;
        long expectedTotal = 0L;
        int page = 1;
        while (true) {
            if (checkPausedOrCancelled(task, control)) {
                return totalRows;
            }
            Map<String, Object> pageResult = puppetNodeSqlService.queryTable(
                    puppetNode, connection, tableRef,
                    Integer.valueOf(page), Integer.valueOf(PAGE_SIZE),
                    header, Collections.<Map<String, Object>>emptyList(),
                    Collections.<Map<String, Object>>emptyList(), Boolean.valueOf(page == 1), null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) pageResult.get("rows");
            @SuppressWarnings("unchecked")
            Map<String, Object> pagination = (Map<String, Object>) pageResult.get("pagination");
            if (pagination != null && pagination.get("total") != null) {
                expectedTotal = toLong(pagination.get("total"));
            }
            if (header.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns = (List<Map<String, Object>>) pageResult.get("columns");
                header.addAll(extractColumnNames(columns));
                if (!header.isEmpty() && totalRows == 0L) {
                    writer.write(csvLine(header));
                    writer.newLine();
                }
            }
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                writer.write(csvLine(valuesForHeader(row, header)));
                writer.newLine();
                totalRows++;
            }
            if (expectedTotal > 0L) {
                int progress = startProgress + (int) Math.min(endProgress - startProgress,
                        (totalRows * (endProgress - startProgress)) / Math.max(1L, expectedTotal));
                updateTask(task, "RUNNING", progress, null);
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return totalRows;
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

    private List<SqlObjectRef> resolveExportTableRefs(SqlCapable puppetNode,
                                                      Map<String, Object> connection,
                                                      SqlObjectRef namespaceRef,
                                                      List<SqlObjectRef> selectedTableRefs) throws Exception {
        if (selectedTableRefs != null && !selectedTableRefs.isEmpty()) {
            return selectedTableRefs.stream()
                    .filter(ref -> ref != null && !isBlank(ref.name()))
                    .distinct()
                    .sorted(Comparator.comparing(this::refSortKey)).toList();
        }
        Map<String, Object> result = puppetNodeSqlService.getTables(
                puppetNode, connection, namespaceRef);
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
        task.put("downloadPath", toUserRelativePath(userId, finalPath));
        task.put("downloadUrl", buildDownloadUrl(toUserRelativePath(userId, finalPath), fileName));
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
        if (!isEmpty(ref.catalog())) parts.add(ref.catalog());
        if (!isEmpty(ref.schema())) parts.add(ref.schema());
        if (!isEmpty(ref.name())) parts.add(ref.name());
        return parts.isEmpty() ? "default" : String.join("_", parts);
    }

    private String refSortKey(SqlObjectRef ref) {
        if (ref == null) return "";
        return safeString(ref.catalog()) + "\u0000" + safeString(ref.schema())
                + "\u0000" + safeString(ref.name());
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isBlank();
    }

    private void updateTask(Map<String, Object> task, String status, int progress, String error) {
        task.put("status", status);
        task.put("progress", Integer.valueOf(Math.max(0, Math.min(100, progress))));
        if ("RUNNING".equals(status) && task.get("startTime") == null) {
            task.put("startTime", Long.valueOf(System.currentTimeMillis()));
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
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
            Path metaFile = taskMetaFile(userId, taskId);
            Files.createDirectories(metaFile.getParent());
            Files.write(metaFile, JsonUtil.toJsonString(task).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireTask(String userId, String taskId) throws Exception {
        Map<String, Object> live = liveTasks.get(taskId);
        if (live != null) {
            return live;
        }
        Path metaFile = taskMetaFile(userId, taskId);
        if (!Files.exists(metaFile)) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        Map<String, Object> task = (Map<String, Object>) JsonUtil.fromJsonString(new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8), HashMap.class);
        liveTasks.put(taskId, task);
        return task;
    }

    private Path taskMetaFile(String userId, String taskId) {
        return userBaseDir(userId).resolve(EXPORT_DIR).resolve(TASKS_DIR).resolve(taskId).resolve("meta.json");
    }

    private Path taskWorkDir(String userId, String taskId) {
        return userBaseDir(userId).resolve(EXPORT_DIR).resolve(TASKS_DIR).resolve(taskId).resolve("work");
    }

    private Path resolveUniqueExportPath(String userId, String fileName) throws Exception {
        Path dir = userBaseDir(userId).resolve(EXPORT_DIR);
        Files.createDirectories(dir);
        Path candidate = dir.resolve(fileName).normalize();
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i <= 9999; i++) {
            Path alt = dir.resolve(stem + "(" + i + ")" + ext).normalize();
            if (!Files.exists(alt)) {
                return alt;
            }
        }
        return dir.resolve(stem + "_" + System.currentTimeMillis() + ext).normalize();
    }

    private Path userBaseDir(String userId) {
        return new File(new File(PuppetNodeSessionWorkDirUtil.getRootDir(), "users"), userId).toPath().toAbsolutePath().normalize();
    }

    private String toUserRelativePath(String userId, Path path) {
        Path base = userBaseDir(userId);
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(base)) {
            return "";
        }
        return base.relativize(target).toString().replace(File.separatorChar, '/');
    }

    private String buildDownloadUrl(String relativePath, String fileName) {
        return "/user/file/download?path=" + urlEncode(relativePath) + "&filename=" + urlEncode(fileName);
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private void writeJson(Path path, Object data) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void zipDirectory(Path sourceDir, Path zipFile) throws Exception {
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(sourceDir)) {
            stream.forEach(paths::add);
        }
        Collections.sort(paths, Comparator.comparing(Path::toString));
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            for (Path path : paths) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                String entryName = sourceDir.relativize(path).toString().replace(File.separatorChar, '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }

    private List<String> extractColumnNames(List<Map<String, Object>> columns) {
        List<String> names = new ArrayList<String>();
        if (columns == null) {
            return names;
        }
        for (Map<String, Object> column : columns) {
            Object name = column.get("label");
            if (name == null || String.valueOf(name).isBlank()) {
                name = column.get("name");
            }
            if (name != null && !String.valueOf(name).isBlank()) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    private List<String> valuesForHeader(Map<String, Object> row, List<String> header) {
        List<String> values = new ArrayList<String>();
        for (String key : header) {
            Object value = row.get(key);
            values.add(value == null ? "" : String.valueOf(value));
        }
        return values;
    }

    private String csvLine(List<String> values) {
        List<String> items = new ArrayList<String>();
        for (String value : values) {
            String text = value == null ? "" : value;
            items.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", items);
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "export.dat";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_");
    }

    private String timestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
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

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text);
    }

    private static final class TaskControl {
        private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    }
}
