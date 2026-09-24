package org.leo.core.manager;

import org.leo.core.entity.Plugin;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private static final PluginManager INSTANCE = new PluginManager();
    private final Map<String, Plugin> plugins = new HashMap<>();

    public static PluginManager getInstance() {
        return INSTANCE;
    }

    private PluginManager() { }

    public void init(String path, String pluginEncryptKey) {
        if (path == null || path.isBlank()) {
            logger.warn("插件路径为空，跳过插件加载");
            return;
        }
        File directory = new File(path);
        if (!directory.exists() && !directory.mkdirs()) {
            logger.warn("插件目录创建失败: {}", path);
            return;
        }
        File[] files = directory.listFiles(File::isFile);
        if (files == null) {
            logger.warn("无法读取插件目录: {}", path);
            return;
        }
        for (File file : files) {
            try {
                String json = AesUtil.decrypt(Files.readString(file.toPath()), pluginEncryptKey);
                installPlugin((Plugin) JsonUtil.fromJsonString(json, Plugin.class));
                logger.debug("插件加载成功: {}", file.getName());
            } catch (Exception e) {
                logger.error("插件加载异常: {}", file.getName(), e);
            }
        }
        logger.info("插件加载完成，共加载 {} 个插件", plugins.size());
    }

    public void installPlugin(Plugin plugin) {
        plugin.setRuntime(plugin.resolveRuntime());
        plugins.put(plugin.getPluginId(), plugin);
    }

    public Plugin getPluginById(String id) {
        return plugins.get(id);
    }

    public void unload(String pluginId) {
        plugins.remove(pluginId);
    }

    public List<Plugin> getPluginAsList() {
        return new ArrayList<>(plugins.values());
    }

    public List<Plugin> getPluginAsListByType(String type) {
        return plugins.values().stream()
                .filter(plugin -> Objects.equals(plugin.getPluginType(), type))
                .toList();
    }
}
