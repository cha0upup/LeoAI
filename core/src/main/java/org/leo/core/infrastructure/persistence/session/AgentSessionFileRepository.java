package org.leo.core.infrastructure.persistence.session;

import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-scoped file listing and preview cache persistence. */
@Repository
public class AgentSessionFileRepository {

    private static final String FILEINFO_JSON = "fileinfo.json";
    private final AtomicFileStore fileStore;

    public AgentSessionFileRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public File saveFileList(String sessionId, String requestedPath, Map<String, Object> results) {
        if (results == null) return null;
        Object code = results.get("code");
        if (!(code instanceof Number number) || number.intValue() != 200) return null;
        String serverPath = text(results.get("absolutePath"));
        if (serverPath == null) serverPath = requestedPath == null ? "root" : requestedPath;
        try {
            File fileDir = AgentNodeSessionWorkDirUtil.getSessionFileDir(sessionId);
            Path base = fileDir.toPath().toAbsolutePath().normalize();
            String relative = "/".equals(serverPath) ? "" : AgentNodeSessionWorkDirUtil.toRelativePathUnderFile(serverPath);
            Path target = relative.isEmpty() ? base : base.resolve(relative).normalize();
            if (!target.startsWith(base)) return null;
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("path", requestedPath == null ? "root" : requestedPath);
            structured.put("absolutePath", results.get("absolutePath"));
            structured.put("listTime", Instant.now().toString());
            structured.put("count", results.get("count"));
            structured.put("fileList", results.get("fileList"));
            return fileStore.writeJson(target.resolve(FILEINFO_JSON).toFile(), structured);
        } catch (Exception e) {
            return null;
        }
    }

    public File savePreview(String sessionId, String filePath, byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            File fileDir = AgentNodeSessionWorkDirUtil.getSessionFileDir(sessionId);
            Path base = fileDir.toPath().toAbsolutePath().normalize();
            Path local = base.resolve(AgentNodeSessionWorkDirUtil.toRelativePathUnderFile(filePath)).normalize();
            if (!local.startsWith(base)) return null;
            Files.createDirectories(local.getParent());
            return Files.write(local, data).toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
