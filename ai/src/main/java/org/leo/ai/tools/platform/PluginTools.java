package org.leo.ai.tools.platform;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.decompiler.DecompilerUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.leo.ai.agent.AiToolException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.leo.ai.tools.platform.PlatformToolArguments.*;

@Component
@AiToolAccess(AiToolAccess.Level.ADMIN)
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class PluginTools {

    private static final String PLUGIN_FILE_EXTENSION = ".plugin";
    private static final String PLUGIN_DIR_NAME = "plugin";
    private static final String DEFAULT_VERSION = "1.0";
    private static final String DEFAULT_PLUGIN_PREFIX = "Plugin_";
    private static final String SAFE_CHAR_REGEX = "[^A-Za-z0-9_-]";

    /** 字节码插件类型：bytecode 字段存 JVM .class 字节码，需经反编译校验。 */
    private static final String PLUGIN_TYPE_JAVA = "java";

    private final PluginManager pluginManager;

    public PluginTools(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Tool("列出平台插件摘要。pluginType 可选；为空返回全部插件。默认不返回 bytecode。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> listPlugins(
            @P(value = "可选插件类型，如 java/js/groovy/python", required = false)
            String pluginType) {
        List<Plugin> plugins = isBlank(pluginType)
                ? pluginManager.getPluginAsList()
                : pluginManager.getPluginAsListByType(pluginType.trim());
        return toPluginSummaries(plugins, false);
    }

    @Tool("根据 pluginId 获取插件详情。includeBytecodeBase64=true 时额外返回 base64 编码后的 bytecode。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> getPluginById(
            @P("插件 ID") String pluginId,
            @P(value = "是否返回 bytecodeBase64；默认 false",
                    required = false, defaultValue = "false") Boolean includeBytecodeBase64) {
        Plugin plugin = pluginManager.getPluginById(requireNonBlank(pluginId, "pluginId不能为空"));
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在");
        }
        return toPluginMap(plugin, Boolean.TRUE.equals(includeBytecodeBase64));
    }

    @Tool(name = "addPlugin",
          value = "创建并保存新的平台插件。pluginType=java 时 bytecodeBase64 必填且必须是合法字节码；"
                + "pluginType 为 js/groovy/python 等脚本类型时改为传 scriptContent（脚本明文）。"
                + "未传 version 时默认 1.0；pluginId 自动派生（java 取类名，脚本取 pluginName）。")
    public Map<String, Object> addPlugin(
                                         @P("创建人用户 ID") String userId,
                                         @P(value = "插件名称；脚本插件必填", required = false) String pluginName,
                                         @P(value = "插件描述", required = false) String pluginDescription,
                                         @P(value = "版本；默认 1.0", required = false,
                                                 defaultValue = "1.0") String version,
                                         @P(value = "Java 字节码 base64，仅 java 类型必填", required = false)
                                         String bytecodeBase64,
                                         @P(value = "脚本明文，js/groovy/python 等脚本类型必填", required = false)
                                         String scriptContent,
                                         @P(value = "参数示例", required = false) String paramsDemo,
                                         @P("插件类型：java/js/groovy/python 等") String pluginType,
                                         @P(value = "备注", required = false) String remark) throws Exception {
        Plugin plugin = new Plugin();
        plugin.setPluginName(trimToNull(pluginName));
        plugin.setPluginDescription(trimToNull(pluginDescription));
        plugin.setVersion(defaultIfBlank(version, DEFAULT_VERSION));
        plugin.setParamsDemo(trimToNull(paramsDemo));
        plugin.setPluginType(trimToNull(pluginType));
        plugin.setRuntime("php".equalsIgnoreCase(plugin.getPluginType()) ? "php" : "java");
        plugin.setRemark(trimToNull(remark));
        plugin.setCreateUserId(requireNonBlank(userId, "userId不能为空"));
        plugin.setCreateTime(String.valueOf(System.currentTimeMillis()));

        boolean isJava = isJavaPlugin(plugin.getPluginType());
        byte[] payloadBytes;
        String identifier;
        if (isJava) {
            payloadBytes = decodeAndValidateBytecode(bytecodeBase64);
            identifier = DecompilerUtil.extractClassName(payloadBytes);
        } else {
            payloadBytes = encodeScriptContent(scriptContent);
            identifier = plugin.getPluginName();
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("脚本插件 pluginName 不能为空（用于派生 pluginId）");
            }
        }
        plugin.setBytecode(payloadBytes);
        plugin.setPluginId(generatePluginId(identifier, plugin.getVersion()));

        pluginManager.installPlugin(plugin);
        savePlugin(plugin);
        return buildResult("created", plugin.getPluginId(), plugin.getPluginName());
    }

    @Tool(name = "updatePlugin",
          value = "更新已有插件。pluginId 必填。java 类型可重新提交 bytecodeBase64；"
                + "脚本类型可重新提交 scriptContent。识别变化后会按类名/插件名 + 版本重新生成 pluginId。")
    public Map<String, Object> updatePlugin(
                                            @P("待更新插件 ID") String pluginId,
                                            @P(value = "新插件名称", required = false) String pluginName,
                                            @P(value = "新插件描述", required = false) String pluginDescription,
                                            @P(value = "新版本", required = false) String version,
                                            @P(value = "Java 字节码 base64，仅 java 类型有效", required = false)
                                            String bytecodeBase64,
                                            @P(value = "脚本明文，仅脚本类型有效", required = false)
                                            String scriptContent,
                                            @P(value = "新参数示例", required = false) String paramsDemo,
                                            @P(value = "新插件类型", required = false) String pluginType,
                                            @P(value = "新备注", required = false) String remark) throws Exception {
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
            existing.setRuntime("php".equalsIgnoreCase(existing.getPluginType()) ? "php" : "java");
        }
        if (remark != null) {
            existing.setRemark(trimToNull(remark));
        }

        boolean isJava = isJavaPlugin(existing.getPluginType());
        byte[] newPayload = null;
        String identifier = null;
        if (isJava && !isBlank(bytecodeBase64)) {
            newPayload = decodeAndValidateBytecode(bytecodeBase64);
            identifier = DecompilerUtil.extractClassName(newPayload);
        } else if (!isJava && !isBlank(scriptContent)) {
            newPayload = encodeScriptContent(scriptContent);
            identifier = existing.getPluginName();
        }
        if (newPayload != null) {
            existing.setBytecode(newPayload);
            String newPluginId = generatePluginId(identifier, existing.getVersion());
            if (!originalPluginId.equals(newPluginId)) {
                pluginManager.unload(originalPluginId);
                existing.setPluginId(newPluginId);
                deletePluginFileIfExists(originalSafeFileName);
            }
        }

        existing.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        pluginManager.installPlugin(existing);
        savePlugin(existing);
        return buildResult("updated", existing.getPluginId(), existing.getPluginName());
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定平台插件。")
    public Map<String, Object> deletePlugin(@P("待删除插件 ID") String pluginId) {
        Plugin plugin = pluginManager.getPluginById(requireNonBlank(pluginId, "pluginId不能为空"));
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在");
        }
        String safeFileName = getSafeFileName(plugin.getPluginId());
        File pluginFile = new File(resolvePluginDir(), safeFileName);
        if (!pluginFile.exists() || !pluginFile.isFile()) {
            throw AiToolException.fatal(
                    "PLUGIN_STORAGE_INCONSISTENT",
                    "插件记录存在，但插件文件不存在。",
                    null);
        }
        if (!pluginFile.delete()) {
            throw AiToolException.fatal(
                    "PLUGIN_DELETE_FAILED",
                    "插件文件删除失败。",
                    null);
        }
        pluginManager.unload(plugin.getPluginId());
        return buildResult("deleted", plugin.getPluginId(), plugin.getPluginName());
    }

    @Tool(name = "decompilePluginBytecode",
          value = "查看插件内容。pluginType=java 时反编译字节码为 Java 源码；"
                + "脚本类型直接返回 UTF-8 文本。pluginType 可省略，默认按 java 处理。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> decompilePluginBytecode(
            @P("插件内容的 Base64") String bytecodeBase64,
            @P(value = "插件类型：java（默认）/ js / groovy / python …", required = false)
            String pluginType) throws Exception {
        String normalized = requireNonBlank(bytecodeBase64, "bytecodeBase64不能为空");
        byte[] bytes = Base64.getDecoder().decode(normalized);
        HashMap<String, Object> result = new HashMap<>();
        if (isJavaPlugin(pluginType)) {
            result.put("javaCode", DecompilerUtil.decompile(bytes));
            result.put("className", DecompilerUtil.extractClassName(bytes));
        } else {
            result.put("scriptText", new String(bytes, StandardCharsets.UTF_8));
        }
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

    /** 脚本类型把 scriptContent 文本以 UTF-8 存入 bytecode 字段。 */
    private byte[] encodeScriptContent(String scriptContent) {
        return requireNonBlank(scriptContent, "scriptContent不能为空")
                .getBytes(StandardCharsets.UTF_8);
    }

    private boolean isJavaPlugin(String pluginType) {
        return PLUGIN_TYPE_JAVA.equalsIgnoreCase(requireNonBlank(pluginType, "pluginType不能为空"));
    }

    private void savePlugin(Plugin plugin) throws Exception {
        File pluginDir = resolvePluginDir();
        if (!pluginDir.exists() && !pluginDir.mkdirs()) {
            throw AiToolException.fatal(
                    "PLUGIN_STORAGE_UNAVAILABLE",
                    "插件存储目录不可用。",
                    null);
        }
        String safeName = getSafeFileName(plugin.getPluginId());
        Files.writeString(new File(pluginDir, safeName).toPath(),
                AesUtil.encrypt(plugin.toString(), LeoConfig.getPluginEncryptKey()));
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
}
