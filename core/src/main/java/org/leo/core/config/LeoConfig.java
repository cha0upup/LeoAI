package org.leo.core.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;


@Component
public class LeoConfig {

    public static final String DEFAULT_VFS_PATH = "root";
    public static final String DEFAULT_PLUGIN_ENCRYPT_KEY = "ikunikunikunikun";

    // 静态全局变量
    private static String VFS_PATH;
    private static String PLUGIN_ENCRYPT_KEY;

    // 注入非静态变量
    @Value("${VfsPath:" + DEFAULT_VFS_PATH + "}")
    private String vfsPath;

    @Value("${PluginEncryptKey:" + DEFAULT_PLUGIN_ENCRYPT_KEY + "}")
    private String pluginEncryptKey;

    // 初始化静态变量
    @PostConstruct
    public void init() {
        VFS_PATH = this.vfsPath;
        PLUGIN_ENCRYPT_KEY = this.pluginEncryptKey;
    }

    // 静态 getter，其他模块可直接访问
    public static String getVfsPath() {
        return VFS_PATH;
    }

    public static String getPluginEncryptKey() {
        return PLUGIN_ENCRYPT_KEY;
    }

    public String getConfiguredVfsPath() {
        return vfsPath;
    }

    public String getConfiguredPluginEncryptKey() {
        return pluginEncryptKey;
    }
}
