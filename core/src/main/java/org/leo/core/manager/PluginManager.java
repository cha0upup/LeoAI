package org.leo.core.manager;

import org.leo.core.entity.Plugin;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private static final PluginManager INSTANCE = new PluginManager();

    public static PluginManager getInstance() {
        return INSTANCE;
    }

    private PluginManager() {
        this.plugins = new HashMap<String, Plugin>();
    }

    private final Map<String, Plugin> plugins;

    public void init(String path, String pluginEncryptKey) throws Exception {
        if (path == null || path.isBlank()) {
            logger.warn("插件路径为空，跳过插件加载");
            return;
        }
        File plugin = new File(path);
        // 如果目录不存在，尝试创建
        if (!plugin.exists()) {
            logger.info("插件目录不存在，尝试创建: {}", path);
            boolean created = plugin.mkdirs();
            if (created) {
                logger.info("插件目录创建成功: {}", path);
                // 目录创建成功，继续执行后续逻辑（加载插件）
            } else {
                logger.warn("插件目录创建失败: {}", path);
                // 创建失败，无法继续加载插件
                return;
            }
        }
        // 检查是否是目录（可能是创建后，或者原本就存在）
        if (!plugin.isDirectory()) {
            logger.warn("插件路径不是目录，跳过插件加载: {}", path);
            return;
        }
        
        File[] pluginFiles = plugin.listFiles();
        // 检查 listFiles() 返回的结果
        if (pluginFiles == null) {
            logger.warn("无法读取插件目录内容，可能没有读取权限: {}", path);
            return;
        }
        
        // 遍历插件文件
        for (File f : pluginFiles) {
            // 跳过目录，只处理文件
            if (f.isDirectory()) {
                continue;
            }
            
            FileInputStream fileInputStream = null;
            try {
                fileInputStream = new FileInputStream(f);
                byte[] fileBytes = new byte[(int) f.length()];
                fileInputStream.read(fileBytes);
                String b = AesUtil.decrypt(new String(fileBytes, "utf-8"), pluginEncryptKey);
                Plugin componentPlugin = (Plugin) JsonUtil.fromJsonString(b, Plugin.class);
                this.plugins.put(componentPlugin.getPluginId(), componentPlugin);
                logger.debug("插件加载成功: {}", f.getName());
            } catch (Exception e) {
                logger.error("插件加载异常: {}", f.getName(), e);
            } finally {
                if (fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (Exception e) {
                        logger.warn("关闭文件流失败: {}", f.getName(), e);
                    }
                }
            }
        }
        
        logger.info("插件加载完成，共加载 {} 个插件", this.plugins.size());
    }
    public boolean inStallPlugin(Plugin plugin){
        this.plugins.put(plugin.getPluginId(),plugin);
        return true;
    }

    public Plugin getPluginById(String id){
       return this.plugins.get(id);
    }
    public void unload(String pluginId) {
        plugins.remove(pluginId);
    }

    public ArrayList<Plugin> getPluginAsList() {
        ArrayList<Plugin> arrayList = new ArrayList<Plugin>();
        for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
            Plugin value = entry.getValue();
            arrayList.add(value);
        }
        return arrayList;
    }

    public ArrayList<Plugin> getPluginAsListByType(String type) {
        ArrayList<Plugin> arrayList = new ArrayList<Plugin>();
        for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
            Plugin value = entry.getValue();
            if (value.getPluginType().equals(type)) {
                arrayList.add(value);
            }
        }
        return arrayList;
    }
}
