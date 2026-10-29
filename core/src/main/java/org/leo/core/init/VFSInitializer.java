package org.leo.core.init;

import jakarta.annotation.PostConstruct;
import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class VFSInitializer {

    private static final Logger logger = LoggerFactory.getLogger(VFSInitializer.class);

    private final Path root;

    public VFSInitializer(LeoConfig config) {
        this.root = Path.of(config.getConfiguredVfsPath());
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
            if (!Files.isReadable(root) || !Files.isWritable(root)) {
                throw new IOException("VFS 目录读写权限异常");
            }
            Path probe = Files.createTempFile(root, ".leo-write-check-", ".tmp");
            try { Files.writeString(probe, "ready"); }
            finally { Files.deleteIfExists(probe); }
        } catch (IOException error) {
            throw new IllegalStateException("VFS 目录初始化失败: " + root, error);
        }
        logger.info("VFSPath 初始化完成: {}", root);
    }

    public Path getRoot() { return root; }
}
