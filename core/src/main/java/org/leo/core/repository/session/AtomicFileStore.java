package org.leo.core.repository.session;

import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small shared store for atomic UTF-8 text and JSON replacement writes. */
@Component
public class AtomicFileStore {

    public File writeJson(File target, Map<String, Object> data) throws Exception {
        if (data == null) throw new IllegalArgumentException("JSON 对象为空");
        return writeText(target, JsonUtil.toJsonString(data));
    }

    public File writeText(File target, String content) throws Exception {
        if (target == null) throw new IllegalArgumentException("目标文件不能为空");
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content == null ? "" : content));
        Path path = target.toPath().toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + target.getName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // A provider without atomic replacement fails without touching the previous target.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return path.toFile();
        } catch (Exception | Error error) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readJsonMap(File file) {
        String content = readText(file);
        if (content == null) return null;
        try {
            Object parsed = JsonUtil.fromJsonString(content, Object.class);
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON 根节点应为对象");
            return new LinkedHashMap<>((Map<String, Object>) parsed);
        } catch (RuntimeException error) {
            throw new FileStoreException(FileStoreException.Reason.INVALID_CONTENT, file.toPath(), error);
        }
    }

    public String readText(File file) {
        if (file == null) throw new IllegalArgumentException("目标文件为空");
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (CharacterCodingException error) {
            throw new FileStoreException(FileStoreException.Reason.INVALID_CONTENT, file.toPath(), error);
        } catch (IOException | SecurityException error) {
            throw new FileStoreException(FileStoreException.Reason.READ_FAILED, file.toPath(), error);
        }
    }
}
