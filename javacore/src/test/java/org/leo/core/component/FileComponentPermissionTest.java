package org.leo.core.component;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentTestSupport.call;
import static org.leo.core.component.ComponentTestSupport.component;

class FileComponentPermissionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
            "false, read", "true, read",
            "false, write", "true, write",
            "false, execute", "true, execute"
    })
    void deniedPermissionChecksPreserveFileMetadata(boolean payload, String denied) throws Exception {
        Path path = Files.writeString(directory.resolve("autologin_sso.jsp"), "original content");
        File original = path.toFile();
        long modified = original.lastModified();
        // Simulate a security policy rejecting one permission query without changing JVM-wide policy.
        File restricted = new File(path.toString()) {
            @Override
            public boolean canRead() {
                if ("read".equals(denied)) throw new SecurityException("read " + getName() + " is forbidden");
                return super.canRead();
            }

            @Override
            public boolean canWrite() {
                if ("write".equals(denied)) throw new SecurityException("write " + getName() + " is forbidden");
                return super.canWrite();
            }

            @Override
            public boolean canExecute() {
                if ("execute".equals(denied)) throw new SecurityException("execute " + getName() + " is forbidden");
                return super.canExecute();
            }
        };

        Map<?, ?> entry = (Map<?, ?>) call(component("FileComponent", payload), "getFileInfoMap",
                new Class<?>[]{File.class}, restricted);

        assertEquals(original.getName(), entry.get("name"));
        assertEquals(original.getAbsolutePath(), entry.get("path"));
        assertEquals(original.length(), entry.get("size"));
        assertEquals(modified, entry.get("modified"));
        assertEquals(true, entry.get("isFile"));
        assertEquals(false, entry.get("isDirectory"));
        assertEquals(true, entry.get("exists"));
        assertEquals("jsp", entry.get("extension"));
        assertEquals(!"read".equals(denied) && original.canRead(), entry.get("canRead"));
        assertEquals(!"write".equals(denied) && original.canWrite(), entry.get("canWrite"));
        assertEquals(!"execute".equals(denied) && original.canExecute(), entry.get("canExecute"));
        assertEquals("original content", Files.readString(path));
        assertEquals(modified, original.lastModified());
    }
}
