package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FileService extends ComponentService {

    public FileService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        return call("FileDownloadComponent", null, "path", Objects.requireNonNull(path),
                "size", size, "offset", offset);
    }

    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        return call("FileUploadComponent", null, "path", Objects.requireNonNull(path),
                "offset", offset, "data", data);
    }

    public Map<String, Object> compress(String src, String des, String excludePattern) throws Exception {
        return call("CompressComponent", null, "src", Objects.requireNonNull(src),
                "des", Objects.requireNonNull(des), "exclude", optionalText(excludePattern, true));
    }

    public Map<String, Object> decompress(String src, String des, String format) throws Exception {
        return call("DecompressComponent", null, "src", Objects.requireNonNull(src),
                "des", Objects.requireNonNull(des), "format", optionalText(format, true));
    }

    public Map<String, Object> getFileList(String path) throws Exception {
        return call("FileComponent", "list", "path", Objects.requireNonNull(path));
    }

    public Map<String, Object> getFileSystemProfile() throws Exception {
        return call("FileComponent", "profile");
    }

    public Map<String, Object> getFileMD5(String path) throws Exception {
        return call("FileComponent", "checksum", "path", Objects.requireNonNull(path));
    }

    public Map<String, Object> createDir(String dirName) throws Exception {
        return call("FileComponent", "createDirectory", "path", Objects.requireNonNull(dirName));
    }

    public Map<String, Object> deleteFile(String path) throws Exception {
        return call("FileComponent", "delete", "path", Objects.requireNonNull(path));
    }

    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        return call("FileComponent", "copy", "path", Objects.requireNonNull(srcPath),
                "destPath", Objects.requireNonNull(destPath), "conflictStrategy", optionalText(conflictStrategy, false));
    }

    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        return call("FileComponent", "move", "path", Objects.requireNonNull(srcPath),
                "newPath", Objects.requireNonNull(newPath), "conflictStrategy", optionalText(conflictStrategy, false));
    }

    public Map<String, Object> createFile(String path, String content) throws Exception {
        return call("FileComponent", "createFile", "path", Objects.requireNonNull(path),
                "content", content.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> editFile(String path, String content) throws Exception {
        return call("FileComponent", "edit", "path", Objects.requireNonNull(path),
                "content", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String optionalText(String value, boolean trim) {
        if (value == null || value.isBlank()) return null;
        return trim ? value.trim() : value;
    }
}
