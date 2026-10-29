package org.leo.ai.service;

import org.leo.core.init.SeedResourceStore;
import org.leo.core.init.VFSInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Bundled skill paths are managed; other custom/imported directories remain untouched. */
@Component
@Order(20)
public class SkillSeedInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SkillSeedInitializer.class);
    private final VFSInitializer vfs;
    private final SeedResourceStore store;

    public SkillSeedInitializer(VFSInitializer vfs, SeedResourceStore store) {
        this.vfs = vfs;
        this.store = store;
    }

    @Override
    public void run(String... args) throws Exception {
        var seeds = new PathMatchingResourcePatternResolver().getResources("classpath:skills/**/*");
        int total = 0;
        int changed = 0;
        for (var seed : seeds) {
            String uri = seed.getURI().toString().replace('\\', '/');
            if (uri.endsWith("/")) continue;
            if (!seed.isReadable()) throw new java.io.IOException("内置 Skill 资源读取失败: " + seed.getFilename());
            int index = uri.lastIndexOf("skills/");
            if (index < 0) throw new IllegalStateException("内置 Skill 资源路径格式异常");
            String relative = uri.substring(index + "skills/".length());
            if (relative.isBlank()) continue;
            var result = store.sync(vfs.getRoot(), "skills/" + relative, seed, true);
            if (result != SeedResourceStore.Result.UNCHANGED) changed++;
            total++;
        }
        if (total == 0) throw new IllegalStateException("内置 Skill 资源为空");
        log.info("[SkillSeed] 同步完成: 更新 {}, 内容未变 {}", changed, total - changed);
    }
}
