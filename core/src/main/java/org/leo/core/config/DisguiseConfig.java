package org.leo.core.config;


import org.leo.core.entity.Disguise;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.init.VFSInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisguiseConfig {
    private final LeoConfig leoConfig;
    private final VFSInitializer vfs;

    public DisguiseConfig(LeoConfig leoConfig, VFSInitializer vfs) {
        this.leoConfig = leoConfig;
        this.vfs = vfs;
    }

    @Bean
    public DisguiseManager disguiseManager() {
        DisguiseManager disguiseManager = DisguiseManager.getInstance();
        String pluginEncryptKey = leoConfig.getConfiguredPluginEncryptKey();
        disguiseManager.init(vfs.getRoot().resolve("disguise").toString(), pluginEncryptKey);
        for (Disguise disguise : JavaBuiltinDisguiseCatalog.createPresets()) {
            if (!disguiseManager.installDisguise(disguise)) {
                throw new IllegalStateException("Java built-in disguise registration failed: "
                        + disguise.getDisguiseId());
            }
        }
        return disguiseManager;
    }
}
