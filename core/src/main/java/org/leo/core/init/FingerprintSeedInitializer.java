package org.leo.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Missing-only fingerprint seeds preserve every existing user rule, including same-name overrides. */
@Component
@Order(10)
public class FingerprintSeedInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(FingerprintSeedInitializer.class);
    private final VFSInitializer vfs;
    private final SeedResourceStore store;

    public FingerprintSeedInitializer(VFSInitializer vfs, SeedResourceStore store) {
        this.vfs = vfs;
        this.store = store;
    }

    @Override
    public void run(String... args) throws Exception {
        var seeds = new PathMatchingResourcePatternResolver().getResources("classpath:fingerprint/*.json");
        if (seeds.length == 0) throw new IllegalStateException("内置指纹资源为空");
        int copied = 0;
        for (var seed : seeds) {
            String name = seed.getFilename();
            if (name == null || name.isBlank()) throw new IllegalStateException("内置指纹资源缺少文件名");
            if (store.sync(vfs.getRoot(), "fingerprint/" + name, seed, false)
                    == SeedResourceStore.Result.CREATED) copied++;
        }
        log.info("[FingerprintSeed] 同步完成: 新增 {}, 保留已有 {}", copied, seeds.length - copied);
    }
}
