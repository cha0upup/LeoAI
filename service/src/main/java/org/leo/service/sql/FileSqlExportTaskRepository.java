package org.leo.service.sql;

import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File-backed metadata adapter; completed snapshots replace metadata instead of truncating it in place. */
@Component
final class FileSqlExportTaskRepository implements SqlExportTaskRepository {
    private final SqlExportWorkspace workspace;

    FileSqlExportTaskRepository(SqlExportWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(SqlExportTaskKey key) throws Exception {
        Path meta = workspace.taskDirectory(key).resolve("meta.json");
        if (!Files.isRegularFile(meta)) throw new IllegalArgumentException("任务不存在: " + key.taskId());
        Map<String, Object> task = JsonUtil.fromJsonString(Files.readString(meta, StandardCharsets.UTF_8), HashMap.class);
        return key.requireOwner(task);
    }

    @Override
    public void save(SqlExportTaskKey key, Map<String, Object> task) throws Exception {
        key.requireOwner(task);
        Path meta = workspace.taskDirectory(key).resolve("meta.json");
        Files.createDirectories(meta.getParent());
        Path temporary = Files.createTempFile(meta.getParent(), "meta-", ".tmp");
        try {
            Files.writeString(temporary, JsonUtil.toJsonString(task), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, meta, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, meta, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public List<String> listTaskIds(String userId) throws Exception {
        Path root = workspace.taskRoot(userId);
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).filter(path -> Files.exists(path.resolve("meta.json")))
                    .map(path -> path.getFileName().toString()).toList();
        }
    }
}
