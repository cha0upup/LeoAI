package org.leo.service.vfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VfsFileAccessTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({
            "users/owner/uploads/file.bin,user",
            "skills/shared.bin,user",
            "users/other/private.bin,admin",
            "users/owner/uploads/file.bin,"
    })
    void resolvesOnlyCallerReadableNamespaces(String path, String privilege) {
        var access = new VfsFileAccess(root::toString);
        assertEquals(root.resolve(path), access.resolveReadable("owner", privilege, path));
    }

    @ParameterizedTest
    @CsvSource({
            "owner,user,users/other/private.bin,无权访问该VFS路径",
            "owner,user,users/owner-prefix/private.bin,无权访问该VFS路径",
            "owner,admin,../outside.bin,非法VFS路径",
            "..,user,skills/shared.bin,用户信息无效"
    })
    void rejectsCrossOwnerAndEscapingPaths(String user, String privilege, String path, String message) {
        var access = new VfsFileAccess(root::toString);
        assertEquals(message, assertThrows(IllegalArgumentException.class,
                () -> access.resolveReadable(user, privilege, path)).getMessage());
    }

    @Test
    void normalizesWirePathsWithoutDecidingWhetherTheSourceExists() {
        var access = new VfsFileAccess(root::toString);
        Path expected = root.resolve("skills/nested/file.bin");
        assertEquals(expected, access.resolveReadable("owner", "user", " /skills/nested/file.bin "));
        assertEquals(expected, access.resolveReadable("owner", "user", "skills\\nested\\file.bin"));
        assertFalse(Files.exists(expected));
    }

    @Test
    void keepsPathValidationAheadOfCallerValidation() {
        var access = new VfsFileAccess(root::toString);
        assertEquals("缺少必要参数: vfsPath", assertThrows(IllegalArgumentException.class,
                () -> access.resolveReadable(null, null, null)).getMessage());
        assertEquals("用户信息无效", assertThrows(IllegalArgumentException.class,
                () -> access.resolveReadable(null, null, "skills/shared.bin")).getMessage());
    }

    @Test
    void observesRootConfigurationChangesWithoutHoldingAStalePath() {
        var configured = new AtomicReference<>(root.toString());
        var access = new VfsFileAccess(configured::get);
        assertEquals(root.resolve("skills/file.bin"), access.resolveReadable("owner", "user", "skills/file.bin"));
        configured.set(root.resolve("next").toString());
        assertEquals(root.resolve("next/skills/file.bin"), access.resolveReadable("owner", "user", "skills/file.bin"));
    }
}
