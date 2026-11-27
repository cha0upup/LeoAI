package org.leo.service.sql;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.file.Path;

/** Export artifact port; the lifecycle service never opens or serializes files itself. */
public interface SqlExportArtifacts {
    Path reserveOutput(String userId, String fileName) throws Exception;
    Path restoreOutput(String userId, String savedPath);
    Path workDirectory(SqlExportTaskKey key);
    String relativePath(String userId, Path path);
    BufferedWriter openCsv(Path path) throws Exception;
    OutputStream openWorkbook(Path path) throws Exception;
    void writeJson(Path path, Object data) throws Exception;
    void zipDirectory(Path sourceDir, Path zipFile) throws Exception;
    long size(Path path) throws Exception;
}
