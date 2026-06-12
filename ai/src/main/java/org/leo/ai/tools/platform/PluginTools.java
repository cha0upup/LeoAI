package org.leo.ai.tools.platform;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.decompiler.DecompilerUtil;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PluginTools {

    private static final String PLUGIN_FILE_EXTENSION = ".plugin";
    private static final String PLUGIN_DIR_NAME = "plugin";
    private static final String DEFAULT_VERSION = "1.0";
    private static final String DEFAULT_PLUGIN_PREFIX = "Plugin_";
    private static final String SAFE_CHAR_REGEX = "[^A-Za-z0-9_-]";

    private final PluginManager pluginManager;

    public PluginTools(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Tool("获取当前平台所有插件摘要。默认不返回 bytecode，适用于浏览插件列表。")
    public List<Map<String, Object>> getPlugins() {
        return toPluginSummaries(pluginManager.getPluginAsList(), false);
    }

    @Tool("根据 pluginId 获取插件详情。includeBytecodeBase64=true 时额外返回 base64 编码后的 bytecode。")
    public Map<String, Object> getPluginById(String pluginId, Boolean includeBytecodeBase64) {
        Plugin plugin = pluginManager.getPluginById(requireNonBlank(pluginId, "pluginId不能为空"));
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在");
        }
        return toPluginMap(plugin, Boolean.TRUE.equals(includeBytecodeBase64));
    }

    @Tool("根据 pluginType 获取插件摘要列表。默认不返回 bytecode。")
    public List<Map<String, Object>> getPluginsByType(String pluginType) {
        return toPluginSummaries(pluginManager.getPluginAsListByType(requireNonBlank(pluginType, "pluginType不能为空")), false);
    }

    @Tool("创建并保存新的平台插件。bytecodeBase64 必填且必须是合法字节码；未传 version 时默认 1.0；pluginId 会按类名和版本自动生成。")
    public Map<String, Object> addPlugin(String userId, String pluginName, String pluginDescription,
                                         String version, String bytecodeBase64, String paramsDemo,
                                         String pluginType, String remark) throws Exception {
        Plugin plugin = new Plugin();
        plugin.setPluginName(trimToNull(pluginName));
        plugin.setPluginDescription(trimToNull(pluginDescription));
        plugin.setVersion(defaultIfBlank(version, DEFAULT_VERSION));
        plugin.setParamsDemo(trimToNull(paramsDemo));
        plugin.setPluginType(trimToNull(pluginType));
        plugin.setRemark(trimToNull(remark));
        plugin.setCreateUserId(requireNonBlank(userId, "userId不能为空"));
        plugin.setCreateTime(String.valueOf(System.currentTimeMillis()));

        byte[] bytecode = decodeAndValidateBytecode(bytecodeBase64);
        plugin.setBytecode(bytecode);
        String className = DecompilerUtil.extractClassName(bytecode);
        plugin.setPluginId(generatePluginId(className, plugin.getVersion()));

        pluginManager.inStallPlugin(plugin);
        savePlugin(plugin);
        return buildResult("created", plugin.getPluginId(), plugin.getPluginName());
    }

    @Tool("更新已有插件。pluginId 必填；如提供新的 bytecodeBase64，会重新校验并在类名或版本变化时生成新的 pluginId。")
    public Map<String, Object> updatePlugin(String pluginId, String pluginName, String pluginDescription,
                                            String version, String bytecodeBase64, String paramsDemo,
                                            String pluginType, String remark) throws Exception {
        String normalizedPluginId = requireNonBlank(pluginId, "pluginId不能为空");
        Plugin existing = pluginManager.getPluginById(normalizedPluginId);
        if (existing == null) {
            throw new IllegalArgumentException("插件不存在");
        }

        String originalPluginId = existing.getPluginId();
        String originalSafeFileName = getSafeFileName(originalPluginId);

        if (pluginName != null) {
            existing.setPluginName(trimToNull(pluginName));
        }
        if (pluginDescription != null) {
            existing.setPluginDescription(trimToNull(pluginDescription));
        }
        if (version != null) {
            existing.setVersion(defaultIfBlank(version, DEFAULT_VERSION));
        }
        if (paramsDemo != null) {
            existing.setParamsDemo(trimToNull(paramsDemo));
        }
        if (pluginType != null) {
            existing.setPluginType(trimToNull(pluginType));
        }
        if (remark != null) {
            existing.setRemark(trimToNull(remark));
        }

        if (!isBlank(bytecodeBase64)) {
            byte[] bytecode = decodeAndValidateBytecode(bytecodeBase64);
            existing.setBytecode(bytecode);
            String className = DecompilerUtil.extractClassName(bytecode);
            String newPluginId = generatePluginId(className, existing.getVersion());
            if (!originalPluginId.equals(newPluginId)) {
                pluginManager.unload(originalPluginId);
                existing.setPluginId(newPluginId);
                deletePluginFileIfExists(originalSafeFileName);
            }
        }

        existing.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        pluginManager.inStallPlugin(existing);
        savePlugin(existing);
        return buildResult("updated", existing.getPluginId(), existing.getPluginName());
    }

    @Tool("删除指定平台插件。")
    public Map<String, Object> deletePlugin(String pluginId) {
        Plugin plugin = pluginManager.getPluginById(requireNonBlank(pluginId, "pluginId不能为空"));
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在");
        }
        String safeFileName = getSafeFileName(plugin.getPluginId());
        File pluginFile = new File(resolvePluginDir(), safeFileName);
        if (!pluginFile.exists() || !pluginFile.isFile()) {
            throw new IllegalArgumentException("插件文件不存在或删除失败");
        }
        if (!pluginFile.delete()) {
            throw new IllegalStateException("插件文件不存在或删除失败");
        }
        pluginManager.unload(plugin.getPluginId());
        return buildResult("deleted", plugin.getPluginId(), plugin.getPluginName());
    }

    @Tool("反编译 Base64 字节码，返回 Java 源码字符串。适用于在安装插件前检查字节码内容。")
    public Map<String, Object> decompilePluginBytecode(String bytecodeBase64) throws Exception {
        byte[] bytecode = decodeAndValidateBytecode(bytecodeBase64);
        HashMap<String, Object> result = new HashMap<>();
        result.put("javaCode", DecompilerUtil.decompile(bytecode));
        result.put("className", DecompilerUtil.extractClassName(bytecode));
        return result;
    }

    private List<Map<String, Object>> toPluginSummaries(List<Plugin> plugins, boolean includeBytecodeBase64) {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        if (plugins == null) {
            return results;
        }
        for (Plugin plugin : plugins) {
            if (plugin != null) {
                results.add(toPluginMap(plugin, includeBytecodeBase64));
            }
        }
        return results;
    }

    private Map<String, Object> toPluginMap(Plugin plugin, boolean includeBytecodeBase64) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("pluginId", plugin.getPluginId());
        result.put("pluginName", plugin.getPluginName());
        result.put("pluginDescription", plugin.getPluginDescription());
        result.put("pluginType", plugin.getPluginType());
        result.put("paramsDemo", plugin.getParamsDemo());
        result.put("version", plugin.getVersion());
        result.put("createUserId", plugin.getCreateUserId());
        result.put("createTime", plugin.getCreateTime());
        result.put("updateTime", plugin.getUpdateTime());
        result.put("remark", plugin.getRemark());
        if (includeBytecodeBase64 && plugin.getBytecode() != null) {
            result.put("bytecodeBase64", Base64.getEncoder().encodeToString(plugin.getBytecode()));
        }
        return result;
    }

    private Map<String, Object> buildResult(String status, String pluginId, String pluginName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("pluginId", pluginId);
        result.put("pluginName", pluginName);
        return result;
    }

    private byte[] decodeAndValidateBytecode(String bytecodeBase64) throws Exception {
        String normalized = requireNonBlank(bytecodeBase64, "bytecodeBase64不能为空");
        byte[] bytecode = Base64.getDecoder().decode(normalized);
        DecompilerUtil.decompile(bytecode);
        return bytecode;
    }

    private void savePlugin(Plugin plugin) throws Exception {
        File pluginDir = resolvePluginDir();
        if (!pluginDir.exists() && !pluginDir.mkdirs()) {
            throw new IllegalStateException("创建插件目录失败: " + pluginDir.getAbsolutePath());
        }
        String safeName = getSafeFileName(plugin.getPluginId());
        try (FileOutputStream fileOutputStream = new FileOutputStream(new File(pluginDir, safeName))) {
            String encrypted = AesUtil.encrypt(plugin.toString(), LeoConfig.getPluginEncryptKey());
            fileOutputStream.write(encrypted.getBytes(StandardCharsets.UTF_8));
            fileOutputStream.flush();
        }
    }

    private void deletePluginFileIfExists(String safeFileName) {
        File oldPluginFile = new File(resolvePluginDir(), safeFileName);
        if (oldPluginFile.exists()) {
            oldPluginFile.delete();
        }
    }

    private File resolvePluginDir() {
        return new File(new File(LeoConfig.getVfsPath()), PLUGIN_DIR_NAME);
    }

    private String generatePluginId(String className, String version) {
        String safeClassName = className == null ? null : className.replaceAll(SAFE_CHAR_REGEX, "_");
        if (isBlank(safeClassName)) {
            safeClassName = DEFAULT_PLUGIN_PREFIX + System.currentTimeMillis();
        }
        String pluginVersion = isBlank(version) ? DEFAULT_VERSION : version.trim();
        return safeClassName + "_" + pluginVersion + PLUGIN_FILE_EXTENSION;
    }

    private String getSafeFileName(String fileName) {
        String safeName = new File(fileName).getName();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\") || !safeName.equals(fileName)) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        return safeName;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
