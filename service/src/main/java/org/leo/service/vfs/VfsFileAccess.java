package org.leo.service.vfs;

import org.leo.core.config.LeoConfig;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        Path path = resolve(vfsPath);
        requireReadPermission(userId, privilege, path);
        return path;
    }

    private Path resolve(String vfsPath) {
        if (isBlank(vfsPath)) {
            throw new IllegalArgumentException("缺少必要参数: vfsPath");
        }
        String normalized = vfsPath.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path base = basePath();
        Path resolved = base.resolve(Paths.get(normalized)).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法VFS路径");
        }
        return resolved;
    }

    private void requireReadPermission(String userId, String privilege, Path resolvedPath) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (resolvedPath == null) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path vfsBase = basePath();
        Path normalizedPath = resolvedPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(vfsBase)) {
            throw new IllegalArgumentException("非法VFS路径");
        }
        if ("admin".equals(privilege)) {
            return;
        }
        Path userBase = userPath(userId);
        Path skillsBase = vfsBase.resolve("skills").toAbsolutePath().normalize();
        if (normalizedPath.startsWith(userBase) || normalizedPath.startsWith(skillsBase)) {
            return;
        }
        throw new IllegalArgumentException("无权访问该VFS路径");
    }

    private Path userPath(String userId) {
        Path usersBase = basePath().resolve("users").toAbsolutePath().normalize();
        Path userBase = usersBase.resolve(userId).normalize();
        if (userBase.equals(usersBase) || !userBase.startsWith(usersBase)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        return userBase;
    }

    private Path basePath() {
        String configured = rootDirectory.get();
        if (isBlank(configured)) configured = LeoConfig.DEFAULT_VFS_PATH;
        return new File(configured).toPath().toAbsolutePath().normalize();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
