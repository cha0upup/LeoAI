package org.leo.core.config;


import org.leo.core.manager.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(PluginConfig.class);
    private final LeoConfig leoConfig;

    public PluginConfig(LeoConfig leoConfig) {
        this.leoConfig = leoConfig;
    }
    
    @Bean
    public PluginManager pluginManager() throws Exception {
        PluginManager pluginManager = PluginManager.getInstance();
        String vfsPath = leoConfig.getConfiguredVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            logger.warn("VFSPath未配置，使用默认路径 'root'");
            vfsPath = LeoConfig.DEFAULT_VFS_PATH;
        }
        String pluginEncryptKey = leoConfig.getConfiguredPluginEncryptKey();
        pluginManager.init(vfsPath + "/plugin", pluginEncryptKey);
        return pluginManager;
    }
}
