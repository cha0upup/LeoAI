package org.leo.service.sql;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SqlExportService {

    private static final String TASKS_DIR = ".sql-export-tasks";
    private static final String EXPORT_DIR = "downloads/sql-export";
    private static final int PAGE_SIZE = 1000;

    private final ConcurrentHashMap<String, Map<String, Object>> liveTasks = new ConcurrentHashMap<String, Map<String, Object>>();
    private final ConcurrentHashMap<String, TaskControl> liveControls = new ConcurrentHashMap<String, TaskControl>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final PuppetNodeSqlService puppetNodeSqlService;

    @Autowired
    public SqlExportService(PuppetNodeSqlService puppetNodeSqlService) {
        this.puppetNodeSqlService = puppetNodeSqlService;
    }

    public Map<String, Object> startTableExport(JavaPuppetNode puppetNode,
                                                String userId,
                                                String sessionId,
                                                Map<String, Object> connection,
                                                String database,
                                                String table,
                                                String format) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (isBlank(table)) {
            throw new IllegalArgumentException("table 不能为空");
        }
        String exportFormat = safeLower(format);
        if (!"csv".equals(exportFormat)) {
            throw new IllegalArgumentException("当前仅支持 csv 格式单表导出");
        }

        String taskId = "sql_export_" + UUID.randomUUID().toString().replace("-", "");
        String fileName = sanitizeFileName((isBlank(database) ? "default" : database) + "_" + table + "_" + timestamp() + ".csv");
        Path finalPath = resolveUniqueExportPath(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "TABLE_EXPORT", fileName, finalPath, database, table);
        task.put("format", exportFormat);
        task.put("status", "PENDING");
        persistTask(task);
        liveTasks.put(taskId, task);
        scheduleTask(task, puppetNode, connection);

        return publicTask(task);
    }

    public Map<String, Object> startDatabaseExport(JavaPuppetNode puppetNode,
                                                   String userId,
                                                   String sessionId,
                                                   Map<String, Object> connection,
                                                   String database,
                                                   List<String> tables,
                                                   Boolean includeStructure,
                                                   Boolean includeData,
                                                   String format) throws Exception {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (isBlank(database)) {
            throw new IllegalArgumentException("database 不能为空");
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
        String fileName = sanitizeFileName(database + "_" + timestamp() + ".zip");
        Path finalPath = resolveUniqueExportPath(userId, fileName);

        Map<String, Object> task = createTask(taskId, userId, sessionId, "DATABASE_EXPORT", fileName, finalPath, database, null);
        task.put("format", exportFormat);
        task.put("status", "PENDING");
        task.put("includeStructure", exportStructure);
        task.put("includeData", exportData);
        task.put("selectedTables", tables == null ? Collections.emptyList() : new ArrayList<String>(tables));
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

    public Map<String, Object> resume(JavaPuppetNode puppetNode,
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
                              final JavaPuppetNode puppetNode,
                              final Map<String, Object> connection) {
        final String taskId = String.valueOf(task.get("taskId"));
        final TaskControl newControl = new TaskControl();
        TaskControl old = liveControls.put(taskId, newControl);
        if (old != null) {
            old.cancelRequested.set(true);
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                String type = String.valueOf(task.get("taskType"));
                Path finalPath = new File(String.valueOf(task.get("finalPath"))).toPath();
                try {
                    if ("TABLE_EXPORT".equals(type)) {
                        runTableExport(taskId, puppetNode, connection,
                                safeString(task.get("database")),
                                safeString(task.get("currentTable")),
                                finalPath,
                                newControl);
                    } else if ("DATABASE_EXPORT".equals(type)) {
                        @SuppressWarnings("unchecked")
                        List<String> tables = task.get("selectedTables") instanceof List<?> ? new ArrayList<String>((List<String>) task.get("selectedTables")) : Collections.<String>emptyList();
                        runDatabaseExport(taskId, puppetNode, connection,
                                safeString(task.get("database")),
                                tables,
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
    }

    private void runTableExport(String taskId,
                                JavaPuppetNode puppetNode,
                                Map<String, Object> connection,
                                String database,
                                String table,
                                Path finalPath,
                                TaskControl control) {
        Map<String, Object> task = liveTasks.get(taskId);
        if (task == null) {
            return;
        }
        try {
            updateTask(task, "RUNNING", 1, null);
            Map<String, Object> columnResult = puppetNodeSqlService.getTableColumns(puppetNode, connection, database, table);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) columnResult.get("columns");

            Files.createDirectories(finalPath.getParent());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(finalPath.toFile()), StandardCharsets.UTF_8))) {
                List<String> header = extractColumnNames(columns);
                if (!header.isEmpty()) {
                    writer.write(csvLine(header));
                    writer.newLine();
                }
                long total = exportTableDataAsCsv(writer, puppetNode, connection, database, table, header, task, 10, 95, control);
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
                                   JavaPuppetNode puppetNode,
                                   Map<String, Object> connection,
                                   String database,
                                   List<String> selectedTables,
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
            List<String> tables = resolveExportTables(puppetNode, connection, database, selectedTables);
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
                String table = tables.get(i);
                task.put("currentTable", table);
                task.put("processedTables", Integer.valueOf(i));
                int progressBase = tables.isEmpty() ? 90 : (int) Math.min(90, 5 + ((double) i / (double) tables.size()) * 85);
                updateTask(task, "RUNNING", progressBase, null);

                if (includeStructure) {
                    Map<String, Object> columnResult = puppetNodeSqlService.getTableColumns(puppetNode, connection, database, table);
                    writeJson(workDir.resolve("structure").resolve(sanitizeFileName(table) + ".columns.json"), columnResult);
                }
                if (includeData) {
                    Path csvPath = workDir.resolve("data").resolve(sanitizeFileName(table) + ".csv");
                    try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        Map<String, Object> columnResult = puppetNodeSqlService.getTableColumns(puppetNode, connection, database, table);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> columns = (List<Map<String, Object>>) columnResult.get("columns");
                        List<String> header = extractColumnNames(columns);
                        if (!header.isEmpty()) {
                            writer.write(csvLine(header));
                            writer.newLine();
                        }
                        exportTableDataAsCsv(writer, puppetNode, connection, database, table, header, task, progressBase, 95, control);
                    }
                }
                if (checkPausedOrCancelled(task, control)) {
                    return;
                }
                task.put("processedTables", Integer.valueOf(i + 1));
                persistTask(task);
            }

            Map<String, Object> manifest = new LinkedHashMap<String, Object>();
            manifest.put("database", database);
            manifest.put("tableCount", Integer.valueOf(tables.size()));
            manifest.put("includeStructure", Boolean.valueOf(includeStructure));
            manifest.put("includeData", Boolean.valueOf(includeData));
            manifest.put("exportTime", Instant.now().toString());
            manifest.put("tables", tables);
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
                                      JavaPuppetNode puppetNode,
                                      Map<String, Object> connection,
                                      String database,
                                      String table,
                                      List<String> header,
                                      Map<String, Object> task,
                                      int startProgress,
                                      int endProgress,
                                      TaskControl control) throws Exception {
        long totalRows = 0L;
        int page = 1;
        while (true) {
            if (checkPausedOrCancelled(task, control)) {
                return totalRows;
            }
            Map<String, Object> pageResult = puppetNodeSqlService.queryTable(
                    puppetNode, connection, database, table, Integer.valueOf(page), Integer.valueOf(PAGE_SIZE),
                    header, Collections.<Map<String, Object>>emptyList(), Collections.<Map<String, Object>>emptyList());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) pageResult.get("rows");
            @SuppressWarnings("unchecked")
            Map<String, Object> pagination = (Map<String, Object>) pageResult.get("pagination");
            long total = pagination == null ? 0L : toLong(pagination.get("total"));
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
            if (total > 0L) {
                int progress = startProgress + (int) Math.min(endProgress - startProgress, (totalRows * (endProgress - startProgress)) / Math.max(1L, total));
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

    private List<String> resolveExportTables(JavaPuppetNode puppetNode,
                                             Map<String, Object> connection,
                                             String database,
                                             List<String> selectedTables) throws Exception {
        if (selectedTables != null && !selectedTables.isEmpty()) {
            return new ArrayList<String>(selectedTables);
        }
        Map<String, Object> result = puppetNodeSqlService.getTables(puppetNode, connection, database);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        List<String> names = new ArrayList<String>();
        if (tables != null) {
            for (Map<String, Object> item : tables) {
                Object name = item.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
        }
        Collections.sort(names);
        return names;
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
        data.put("selectedTables", task.get("selectedTables"));
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
