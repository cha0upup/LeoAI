package org.leo.core.manager;

import org.leo.core.entity.Disguise;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DisguiseManager {

    private static final Logger logger = LoggerFactory.getLogger(DisguiseManager.class);
    private static final DisguiseManager INSTANCE = new DisguiseManager();
    private final Map<String, Disguise> disguises = new HashMap<>();

    public static DisguiseManager getInstance() {
        return INSTANCE;
    }

    private DisguiseManager() { }

    /**
     * 初始化并加载指定目录下的disguise插件
     */
    public void init(String disguisePath, String pluginEncryptKey) {
        if (disguisePath == null || disguisePath.isBlank()) {
            logger.warn("disguise路径为空，跳过disguise加载");
            return;
        }

        File disguiseDir = new File(disguisePath);

        // 创建目录（如果不存在）
        if (!disguiseDir.exists() && !disguiseDir.mkdirs()) {
            logger.warn("disguise目录创建失败: {}", disguisePath);
            return;
        }

        if (!disguiseDir.isDirectory()) {
            logger.warn("disguise路径不是目录，跳过disguise加载: {}", disguisePath);
            return;
        }

        File[] files = disguiseDir.listFiles();
        if (files == null) {
            logger.warn("无法读取disguise目录内容，可能没有权限: {}", disguisePath);
            return;
        }

        int loadedCount = 0;

        for (File file : files) {
            if (!file.isFile()) continue;

            try {
                // 读取整个文件字节并以 UTF-8 解码
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                String encryptedContent = new String(fileBytes, StandardCharsets.UTF_8);

                // 解密并反序列化
                String json = AesUtil.decrypt(encryptedContent, pluginEncryptKey);
                Disguise disguise = (Disguise) JsonUtil.fromJsonString(json, Disguise.class);

                // 初始化插件并加入管理
                disguise.init();
                disguises.put(disguise.getDisguiseId(), disguise);

                logger.debug("disguise加载成功: {}", file.getName());
                loadedCount++;
            } catch (Exception e) {
                logger.error("disguise加载异常: {}", file.getName(), e);
            }
        }

        logger.info("disguise加载完成，共加载 {} 个disguise", loadedCount);
    }

    /**
     * 安装单个Disguise
     */
    public boolean inStallDisguise(Disguise disguise) {
        try {
            disguise.init();
            disguises.put(disguise.getDisguiseId(), disguise);
            return true;
        } catch (Exception e) {
            logger.error("安装disguise失败: {}", disguise.getDisguiseId(), e);
            return false;
        }
    }

    public Disguise getDisguiseById(String disguiseId) {
        return disguises.get(disguiseId);
    }

    public void unload(String disguiseId) {
        disguises.remove(disguiseId);
    }

    public ArrayList<Disguise> getDisguiseAsList() {
        return new ArrayList<>(disguises.values());
    }
}
