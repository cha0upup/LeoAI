package org.leo.service.sql;

import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local artifact adapter. Every output name is reserved before its worker is scheduled. */
@Component
final class FileSqlExportArtifacts implements SqlExportArtifacts {
    private final SqlExportWorkspace workspace;

    FileSqlExportArtifacts(SqlExportWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public Path reserveOutput(String userId, String fileName) throws Exception {
        if (fileName == null || fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.chars().anyMatch(c -> c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)))
            throw new IllegalArgumentException("导出文件名无效");
        Path dir = workspace.exportDirectory(userId);
        Files.createDirectories(dir);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 0; i <= 9999; i++) {
            Path candidate = dir.resolve(i == 0 ? fileName : stem + "(" + i + ")" + ext).normalize();
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException occupied) {
                // Atomic reservation also protects exports that are still queued.
            }
        }
        return Files.createTempFile(dir, stem + "_", ext);
    }

    @Override
    public Path restoreOutput(String userId, String savedPath) {
        if (savedPath == null || savedPath.isBlank()) throw new IllegalArgumentException("导出文件路径无效");
        Path path = Path.of(savedPath).toAbsolutePath().normalize();
        if (!workspace.exportDirectory(userId).equals(path.getParent()))
            throw new IllegalArgumentException("导出文件路径无效");
        return path;
    }

    @Override
    public Path workDirectory(SqlExportTaskKey key) {
        return workspace.taskDirectory(key).resolve("work");
    }

    @Override
    public String relativePath(String userId, Path path) {
        Path base = workspace.userBase(userId);
        Path target = path.toAbsolutePath().normalize();
        return target.startsWith(base) ? base.relativize(target).toString().replace(File.separatorChar, '/') : "";
    }

    @Override
    public BufferedWriter openCsv(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    @Override
    public void writeJson(Path path, Object data) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, JsonUtil.toJsonString(data), StandardCharsets.UTF_8);
    }

    @Override
    public void zipDirectory(Path sourceDir, Path zipFile) throws Exception {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            paths = stream.sorted(Comparator.comparing(Path::toString)).toList();
        }
        Files.createDirectories(zipFile.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path path : paths) {
                if (Files.isDirectory(path)) continue;
                String entryName = sourceDir.relativize(path).toString().replace(File.separatorChar, '/');
                output.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
    }

    @Override
    public long size(Path path) throws Exception {
        return Files.size(path);
    }
}
