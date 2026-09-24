package org.leo.core.init;

import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 启动时将 classpath:fingerprint/*.json 下的内置指纹规则拷贝到 VFS 指纹目录。
 * 已存在的同名文件保留用户规则。
 */
@Component
public class FingerprintSeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FingerprintSeedInitializer.class);

    private static final String FINGERPRINT_DIR_NAME = "fingerprint";
    private static final String SEED_LOCATION_PATTERN = "classpath:fingerprint/*.json";

    @Override
    public void run(String... args) throws Exception {
        File targetDir = new File(new File(LeoConfig.getVfsPath()), FINGERPRINT_DIR_NAME);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            log.warn("[FingerprintSeed] 指纹目录创建失败: {}", targetDir.getAbsolutePath());
            return;
        }

        Resource[] seeds;
        try {
            seeds = new PathMatchingResourcePatternResolver().getResources(SEED_LOCATION_PATTERN);
        } catch (Exception e) {
            log.warn("[FingerprintSeed] 加载内置指纹失败: {}", e.getMessage());
            return;
        }

        int copied = 0;
        int skipped = 0;
        for (Resource seed : seeds) {
            String filename = seed.getFilename();
            if (filename == null || filename.isBlank()) continue;
            File destFile = new File(targetDir, filename);
            if (destFile.exists()) {
                skipped++;
                continue;
            }
            try (InputStream in = seed.getInputStream()) {
                Files.copy(in, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (Exception e) {
                log.warn("[FingerprintSeed] 拷贝失败: {} - {}", filename, e.getMessage());
            }
        }

        log.info("[FingerprintSeed] 内置指纹同步完成: 新增 {} 条, 跳过 {} 条 (已存在)", copied, skipped);
    }

}
