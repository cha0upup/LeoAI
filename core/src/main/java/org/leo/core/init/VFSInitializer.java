package org.leo.core.init;

import jakarta.annotation.PostConstruct;
import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class VFSInitializer {

    private static final Logger logger = LoggerFactory.getLogger(VFSInitializer.class);

    private final LeoConfig config;

    public VFSInitializer(LeoConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        String vfsPath = config.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = System.getProperty("java.io.tmpdir") + "/leo-vfs";
            logger.warn("未配置 VFSPath，使用默认临时目录: {}", vfsPath);
        }

        File vfsDir = new File(vfsPath);
        if (!vfsDir.exists() && !vfsDir.mkdirs()) {
            throw new IllegalStateException("VFSPath目录创建失败: " + vfsDir.getAbsolutePath());
        }

        if (!vfsDir.isDirectory() || !vfsDir.canWrite()) {
            throw new IllegalStateException("VFSPath目录不可用: " + vfsDir.getAbsolutePath());
        }

        logger.info("VFSPath 初始化完成: {}", vfsDir.getAbsolutePath());
    }
}
