package org.leo.core.config;


import org.leo.core.manager.PluginManager;
import org.leo.core.init.VFSInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginConfig {
    
    private final LeoConfig leoConfig;
    private final VFSInitializer vfs;

    public PluginConfig(LeoConfig leoConfig, VFSInitializer vfs) {
        this.leoConfig = leoConfig;
        this.vfs = vfs;
    }
    
    @Bean
    public PluginManager pluginManager() throws Exception {
        PluginManager pluginManager = PluginManager.getInstance();
        String pluginEncryptKey = leoConfig.getConfiguredPluginEncryptKey();
        pluginManager.init(vfs.getRoot().resolve("plugin").toString(), pluginEncryptKey);
        return pluginManager;
    }
}
