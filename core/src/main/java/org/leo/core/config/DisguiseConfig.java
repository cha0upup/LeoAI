package org.leo.core.config;


import org.leo.core.entity.Disguise;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.manager.DisguiseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisguiseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DisguiseConfig.class);
    private final LeoConfig leoConfig;

    public DisguiseConfig(LeoConfig leoConfig) {
        this.leoConfig = leoConfig;
    }

    @Bean
    public DisguiseManager disguiseManager() {
        DisguiseManager disguiseManager = DisguiseManager.getInstance();
        String vfsPath = leoConfig.getConfiguredVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            logger.warn("VFSPath未配置，使用默认路径 'root'");
            vfsPath = LeoConfig.DEFAULT_VFS_PATH;
        }
        String pluginEncryptKey = leoConfig.getConfiguredPluginEncryptKey();
        disguiseManager.init(vfsPath + "/disguise", pluginEncryptKey);
        for (Disguise disguise : JavaBuiltinDisguiseCatalog.createPresets()) {
            if (!disguiseManager.installDisguise(disguise)) {
                throw new IllegalStateException("Java built-in disguise registration failed: "
                        + disguise.getDisguiseId());
            }
        }
        return disguiseManager;
    }
}
