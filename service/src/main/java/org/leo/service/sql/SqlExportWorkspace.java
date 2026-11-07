package org.leo.service.sql;

import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** One filesystem layout shared by the metadata and artifact adapters. */
@Component
final class SqlExportWorkspace {
    private final Supplier<Path> root;

    SqlExportWorkspace() {
        this(() -> AgentNodeSessionWorkDirUtil.getRootDir().toPath());
    }

    SqlExportWorkspace(Supplier<Path> root) {
        this.root = Objects.requireNonNull(root);
    }

    Path userBase(String userId) {
        return root.get().toAbsolutePath().normalize().resolve("users").resolve(SqlExportTaskKey.requireUserId(userId));
    }

    Path exportDirectory(String userId) {
        return userBase(userId).resolve("downloads/sql-export");
    }

    Path taskRoot(String userId) {
        return exportDirectory(userId).resolve(".sql-export-tasks");
    }

    Path taskDirectory(SqlExportTaskKey key) {
        return taskRoot(key.userId()).resolve(key.taskId());
    }
}
