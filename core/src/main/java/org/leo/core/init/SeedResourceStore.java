package org.leo.core.init;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publish complete seed files only. Unknown/user-owned files are never replaced by missing-only seeds. */
@Component
public class SeedResourceStore {
    public enum Result { CREATED, UPDATED, UNCHANGED, PRESERVED }

    public Result sync(Path root, String relativePath, Resource resource, boolean managed) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path relative = Path.of(relativePath);
        Path target = base.resolve(relative).normalize();
        if (relative.isAbsolute() || !target.startsWith(base) || target.equals(base)) {
            throw new IOException("内置资源路径超出目录边界: " + relativePath);
        }
        Files.createDirectories(base);
        Path ancestor = target.getParent();
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(base.toRealPath())) {
            throw new IOException("内置资源目录链接超出边界: " + relativePath);
        }
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(base.toRealPath())) {
            throw new IOException("内置资源目录链接超出边界: " + relativePath);
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !managed) return Result.PRESERVED;
        if (Files.isSymbolicLink(target)) throw new IOException("内置受管文件是符号链接: " + relativePath);
        Path temp = Files.createTempFile(target.getParent(), ".leo-seed-", ".tmp");
        try {
            long expectedLength = resource.contentLength();
            try (var input = resource.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (expectedLength >= 0 && Files.size(temp) != expectedLength) {
                throw new IOException("内置资源长度校验失败: " + relativePath);
            }
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            if (managed) {
                if (exists && Files.mismatch(temp, target) == -1) return Result.UNCHANGED;
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return exists ? Result.UPDATED : Result.CREATED;
            }
            // Atomic create-if-absent: unlike ATOMIC_MOVE, a hard-link publish never replaces a racing writer.
            try { Files.createLink(target, temp); }
            catch (FileAlreadyExistsException raced) { return Result.PRESERVED; }
            catch (UnsupportedOperationException unsupported) {
                throw new IOException("文件系统不支持内置资源原子发布", unsupported);
            }
            return Result.CREATED;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
