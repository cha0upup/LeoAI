package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.json.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class SqlExportFileAdaptersTest {
    @TempDir Path root;
    private final SqlExportWorkspace workspace = new SqlExportWorkspace(() -> root);
    private final FileSqlExportTaskRepository tasks = new FileSqlExportTaskRepository(workspace);
    private final FileSqlExportArtifacts artifacts = new FileSqlExportArtifacts(workspace);
    private final SqlExportTaskKey key = new SqlExportTaskKey("owner", "task");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".", "..", "../owner", "a/b", "a\\b", "C:task", "bad\nidentity"})
    void usesTheSameIdentityRulesForMetadataAndArtifacts(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> new SqlExportTaskKey(invalid, "task"));
        assertThrows(IllegalArgumentException.class, () -> new SqlExportTaskKey("owner", invalid));
        assertThrows(IllegalArgumentException.class, () -> artifacts.reserveOutput(invalid, "file.xlsx"));
    }

    @Test void atomicallyReplacesMetadataAndLeavesNoTemporaryFiles() throws Exception {
        tasks.save(key, metadata("PENDING"));
        tasks.save(key, metadata("PAUSED"));
        assertEquals("PAUSED", tasks.load(key).get("status"));
        assertEquals(List.of("task"), tasks.listTaskIds("owner"));
        try (var files = Files.list(workspace.taskDirectory(key))) {
            assertEquals(List.of("meta.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void rejectsMismatchedMetadataAndKeepsTheLastGoodSnapshot() throws Exception {
        tasks.save(key, metadata("PENDING"));
        var wrong = Map.<String, Object>of("userId", "other", "taskId", "task", "status", "PAUSED");
        assertThrows(IllegalArgumentException.class, () -> tasks.save(key, wrong));
        assertEquals("PENDING", tasks.load(key).get("status"));
        Files.writeString(workspace.taskDirectory(key).resolve("meta.json"), JsonUtil.toJsonString(wrong));
        assertThrows(IllegalArgumentException.class, () -> tasks.load(key));
        assertThrows(IllegalArgumentException.class, () -> tasks.load(new SqlExportTaskKey("other", "task")));
    }

    @Test void failedSerializationPreservesTheLastSnapshotAndCleansTemporaryFiles() throws Exception {
        tasks.save(key, metadata("PENDING"));
        var replacement = metadata("RUNNING");
        try (var json = mockStatic(JsonUtil.class)) {
            json.when(() -> JsonUtil.toJsonString(replacement)).thenThrow(new IllegalStateException("fixture serialization failure"));
            assertThrows(IllegalStateException.class, () -> tasks.save(key, replacement));
        }
        assertEquals("PENDING", tasks.load(key).get("status"));
        try (var files = Files.list(workspace.taskDirectory(key))) {
            assertEquals(List.of("meta.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void readersSeeWholeMetadataSnapshotsDuringConcurrentReplacements() throws Exception {
        tasks.save(key, metadata("initial"));
        var pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) jobs.add(pool.submit(() -> {
                for (int i = 0; i < 30; i++) {
                    try { tasks.save(key, metadata("snapshot-" + i)); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }
            }));
            jobs.add(pool.submit(() -> {
                for (int i = 0; i < 80; i++) {
                    try { assertEquals("owner", tasks.load(key).get("userId")); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }
            }));
            for (Future<?> job : jobs) job.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Test void reservesOutputsAtomicallyEvenWhenWorkersHaveNotStarted() throws Exception {
        Path existing = artifacts.reserveOutput("owner", "same.xlsx");
        Files.writeString(existing, "existing");
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Path>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) jobs.add(pool.submit(() -> artifacts.reserveOutput("owner", "same.xlsx")));
            var paths = new java.util.HashSet<Path>();
            for (Future<Path> job : jobs) assertTrue(paths.add(job.get(20, TimeUnit.SECONDS)));
            assertEquals(12, paths.size());
            assertFalse(paths.contains(existing));
            assertEquals("existing", Files.readString(existing));
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../out.xlsx", "a/b.xlsx", "a\\b.xlsx", "C:out.xlsx", ".", "..", "bad\nname.xlsx"})
    void rejectsOutputNamesThatAreNotSingleFileNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> artifacts.reserveOutput("owner", name));
    }

    @Test void validatesRestoredOutputPathsAndKeepsDownloadPathsUserRelative() throws Exception {
        Path path = artifacts.reserveOutput("owner", "result.xlsx");
        assertEquals(path, artifacts.restoreOutput("owner", path.toString()));
        assertEquals("downloads/sql-export/result.xlsx", artifacts.relativePath("owner", path));
        assertEquals("", artifacts.relativePath("other", path));
        assertThrows(IllegalArgumentException.class, () -> artifacts.restoreOutput("other", path.toString()));
        assertThrows(IllegalArgumentException.class, () -> artifacts.restoreOutput("owner", root.resolve("outside.xlsx").toString()));
        assertNotEquals(artifacts.workDirectory(key), artifacts.workDirectory(new SqlExportTaskKey("other", "task")));
    }

    @Test void writesUtf8ArtifactsAndDeterministicRelativeZipEntries() throws Exception {
        Path work = artifacts.workDirectory(key);
        artifacts.writeJson(work.resolve("manifest.json"), Map.of("name", "中文"));
        byte[] content = {0, 1, -1};
        try (var output = artifacts.openWorkbook(work.resolve("data/orders.xlsx"))) { output.write(content); }
        Path zip = artifacts.reserveOutput("owner", "database.zip");
        artifacts.zipDirectory(work, zip);
        try (var archive = new ZipFile(zip.toFile())) {
            assertEquals(List.of("data/orders.xlsx", "manifest.json"), archive.stream().map(entry -> entry.getName()).toList());
            assertArrayEquals(content, archive.getInputStream(archive.getEntry("data/orders.xlsx")).readAllBytes());
            assertTrue(new String(archive.getInputStream(archive.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8).contains("中文"));
        }
        assertEquals(Files.size(zip), artifacts.size(zip));
    }

    private Map<String, Object> metadata(String status) {
        return Map.of("userId", key.userId(), "taskId", key.taskId(), "status", status);
    }
}
