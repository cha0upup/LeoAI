package org.leo.service.vfs;

import org.leo.core.config.LeoConfig;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.function.Supplier;

/** VFS path and caller read scope; existence and file-kind checks belong to the use case. */
@Component
public final class VfsFileAccess {
    private final Supplier<String> rootDirectory;

    public VfsFileAccess() {
        this(LeoConfig::getVfsPath);
    }

    VfsFileAccess(Supplier<String> rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    /** Resolves the path and enforces caller read scope in one operation. */
    public Path resolveReadable(String userId, String privilege, String vfsPath) {
        if (isBlank(vfsPath)) {
            throw new IllegalArgumentException("缺少必要参数: vfsPath");
        }
        String normalized = vfsPath.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path base = basePath();
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法VFS路径");
        }
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if ("admin".equals(privilege)) {
            return resolved;
        }
        Path usersBase = base.resolve("users");
        Path userBase = usersBase.resolve(userId).normalize();
        if (userBase.equals(usersBase) || !userBase.startsWith(usersBase)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (resolved.startsWith(userBase) || resolved.startsWith(base.resolve("skills"))) {
            return resolved;
        }
        throw new IllegalArgumentException("无权访问该VFS路径");
    }

    private Path basePath() {
        String configured = rootDirectory.get();
        if (isBlank(configured)) configured = LeoConfig.DEFAULT_VFS_PATH;
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
