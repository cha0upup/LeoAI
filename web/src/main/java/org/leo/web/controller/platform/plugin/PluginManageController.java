package org.leo.web.controller.platform.plugin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Plugin;
import org.leo.core.entity.User;
import org.leo.core.manager.PluginManager;
import org.leo.core.util.SafeZipReader;
import org.leo.core.util.decompiler.DecompilerUtil;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.aes.AesUtil;
import org.leo.web.security.AdminOnlyEndpoint;
import org.leo.web.util.DownloadHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件管理控制器
 */
@RestController
@RequestMapping("/platform/plugin-manage")
public class PluginManageController {
    
    // 参数名常量
    private static final String PARAM_PLUGIN_NAME = "pluginName";
    private static final String PARAM_PLUGIN_DESCRIPTION = "pluginDescription";
    private static final String PARAM_VERSION = "version";
    private static final String PARAM_BYTECODE = "bytecode";
    private static final String PARAM_SCRIPT_CONTENT = "scriptContent";
    private static final String PARAM_PARAMS_DEMO = "paramsDemo";
    private static final String PARAM_PLUGIN_TYPE = "pluginType";
    private static final String PARAM_PLUGIN_ID = "pluginId";
    private static final String PARAM_REMARK = "remark";
    private static final String PARAM_RUNTIME = "runtime";
    private static final String PARAM_KIND = "kind";
    private static final String PARAM_LANGUAGE = "language";
    private static final String PARAM_ENTRYPOINT = "entrypoint";
    private static final String PARAM_PARAMETER_SCHEMA = "parameterSchema";
    private static final String PARAM_RISK_LEVEL = "riskLevel";

    /** 字节码插件类型：bytecode 字段是 JVM .class，需经反编译校验。 */
    private static final String PLUGIN_TYPE_JAVA = "java";
    
    // 结果字段常量
    private static final String RESULT_PLUGIN_ID = "pluginId";
    
    // 会话属性常量
    private static final String SESSION_ATTR_USER = "user";
    
    // 文件相关常量
    private static final String PLUGIN_FILE_EXTENSION = ".plugin";
    private static final String PLUGIN_DIR_NAME = "plugin";

    // 默认值常量
    private static final String DEFAULT_VERSION = "1.0";
    private static final String DEFAULT_PLUGIN_PREFIX = "Plugin_";
    
    // 安全字符正则表达式
    private static final String SAFE_CHAR_REGEX = "[^A-Za-z0-9_-]";
    
    @Autowired
    private PluginManager pluginManager;
    /**
     * 从会话中获取用户
     */
    private User getUserFromSession(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_ATTR_USER);
    }
    
    /**
     * 验证并获取安全的文件名
     */
    private String getSafeFileName(String fileName) {
        String safeName = new File(fileName).getName();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\") || !safeName.equals(fileName)) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        return safeName;
    }
    
    /**
     * 生成安全的类名
     */
    private String generateSafeClassName(String className) {
        String safeClassName = className.replaceAll(SAFE_CHAR_REGEX, "_");
        if (safeClassName == null) {
            safeClassName = DEFAULT_PLUGIN_PREFIX + System.currentTimeMillis();
        }
        return safeClassName;
    }
    
    /**
     * 生成插件ID
     */
    private String generatePluginId(String className, String version) {
        String safeClassName = generateSafeClassName(className);
        String pluginVersion = (version == null ) ? DEFAULT_VERSION : version;
        return safeClassName + "_" + pluginVersion + PLUGIN_FILE_EXTENSION;
    }
    
    @RequestMapping(value = "/plugins", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> addPlugin(@RequestBody HashMap<String, Object> params, HttpServletRequest request) throws Exception {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        Plugin componentPlugin = new Plugin();
        componentPlugin.setPluginName((String) params.get(PARAM_PLUGIN_NAME));
        componentPlugin.setPluginDescription((String) params.get(PARAM_PLUGIN_DESCRIPTION));
        componentPlugin.setVersion((String) params.get(PARAM_VERSION));
        componentPlugin.setParamsDemo((String) params.get(PARAM_PARAMS_DEMO));
        componentPlugin.setPluginType((String) params.get(PARAM_PLUGIN_TYPE));
        applyRuntimeMetadata(params, componentPlugin);

        // 字节码插件走 bytecode + 反编译校验，脚本插件走 scriptContent 文本字节
        boolean isJava = isJavaPlugin(componentPlugin.getPluginType());
        byte[] payloadBytes;
        String identifier;
        try {
            if (isJava) {
                payloadBytes = decodeAndValidateBytecode(params.get(PARAM_BYTECODE));
                identifier = DecompilerUtil.extractClassName(payloadBytes);
            } else {
                payloadBytes = encodeScriptContent(params.get(PARAM_SCRIPT_CONTENT));
                identifier = componentPlugin.getPluginName();
            }
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        componentPlugin.setBytecode(payloadBytes);

        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        componentPlugin.setCreateUserId(user.getUserId());
        componentPlugin.setCreateTime(String.valueOf(System.currentTimeMillis()));

        String pluginId = generatePluginId(identifier, componentPlugin.getVersion());
        componentPlugin.setPluginId(pluginId);
        pluginManager.installPlugin(componentPlugin);
        savePlugin(componentPlugin);
        return ApiResponse.success(Map.of(RESULT_PLUGIN_ID, pluginId));
    }

    private boolean isJavaPlugin(String pluginType) {
        if (pluginType == null || pluginType.isBlank()) {
            throw new IllegalArgumentException("pluginType 不能为空");
        }
        return PLUGIN_TYPE_JAVA.equalsIgnoreCase(pluginType.trim());
    }

    /** 解码并反编译校验 Java 字节码。 */
    private byte[] decodeAndValidateBytecode(Object rawBase64) {
        if (!(rawBase64 instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("bytecode参数不能为空且必须是Base64字符串");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bytecode 解码失败: " + e.getMessage());
        }
        try {
            DecompilerUtil.decompile(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("字节码验证失败");
        }
        return bytes;
    }

    /** 脚本类型把 scriptContent 文本以 UTF-8 存入 bytecode 字段。 */
    private byte[] encodeScriptContent(Object rawScript) {
        if (!(rawScript instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("scriptContent不能为空");
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void savePlugin(Plugin plugin) throws Exception {
        if (plugin == null || plugin.getPluginId() == null) {
            throw new IllegalArgumentException("componentPlugin或pluginId不能为空");
        }
        File directory = new File(LeoConfig.getVfsPath(), PLUGIN_DIR_NAME);
        Files.createDirectories(directory.toPath());
        File file = new File(directory, getSafeFileName(plugin.getPluginId()));
        Files.writeString(file.toPath(), AesUtil.encrypt(plugin.toString(), LeoConfig.getPluginEncryptKey()));
    }

    @RequestMapping(value = "/plugins/delete", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> delPlugin(@RequestBody HashMap<String, Object> params) {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        String pluginId = (String) params.get(PARAM_PLUGIN_ID);
        if (pluginId == null) {
            return ApiResponse.badRequest("pluginId不能为空");
        }
        // 安全检查：防止路径遍历攻击
        String safeFileName = getSafeFileName(pluginId);
        File root = new File(LeoConfig.getVfsPath());
        File plugin = new File(root, PLUGIN_DIR_NAME);
        if (!plugin.exists()) {
            plugin.mkdirs();
        }
        File pluginFile = new File(plugin, safeFileName);
        boolean deleted = pluginFile.delete();
        if (deleted) {
            pluginManager.unload(pluginId);
            return ApiResponse.success("插件删除成功");
        } else {
            return ApiResponse.notFound("插件文件不存在或删除失败");
        }
    }

    @RequestMapping(value = "/plugins/update", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> updatePlugin(@RequestBody HashMap<String, Object> params, HttpServletRequest request) throws Exception {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        String pluginId = (String) params.get(PARAM_PLUGIN_ID);
        if (pluginId == null) {
            return ApiResponse.badRequest("pluginId不能为空");
        }
        
        // 安全检查：防止路径遍历攻击
        String safeFileName = getSafeFileName(pluginId);
        
        // 检查插件是否存在
        Plugin existingPlugin = pluginManager.getPluginById(pluginId);
        if (existingPlugin == null) {
            return ApiResponse.notFound("插件不存在");
        }
        
        // 更新插件信息
        updatePluginFields(params, existingPlugin);
        
        // 如果提供了新的字节码，需要验证并更新
        String newPluginId = updateBytecodeIfProvided(params, existingPlugin, pluginId, safeFileName);
        if (newPluginId != null) {
            pluginId = newPluginId;
        }
        
        // 更新时间和用户信息
        existingPlugin.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        
        // 保存插件
        savePlugin(existingPlugin);
        pluginManager.installPlugin(existingPlugin);
        return ApiResponse.success("插件更新成功", Map.of(RESULT_PLUGIN_ID, existingPlugin.getPluginId()));
    }

    @RequestMapping(value = "/plugins", method = RequestMethod.GET)
    public HashMap<String, Object> getPlugin() {
        return ApiResponse.success(pluginManager.getPluginAsList());
    }

    // ── 导出 ──────────────────────────────────────────────────────────────────

    /**
     * 单条导出：GET /plugins/export?pluginId=xxx
     * 直接返回 VFS 中加密的 .plugin 文件，无需解密/重加密。
     */
    @RequestMapping(value = "/plugins/export", method = RequestMethod.GET)
    public ResponseEntity<byte[]> exportPlugin(@RequestParam("pluginId") String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("pluginId 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        try {
            String safeFileName = getSafeFileName(pluginId.trim());
            File pluginFile = new File(new File(LeoConfig.getVfsPath(), PLUGIN_DIR_NAME), safeFileName);
            if (!pluginFile.exists()) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(pluginFile.toPath());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, DownloadHeaders.attachmentValue(safeFileName))
                    .body(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 批量导出：POST /plugins/export/batch
     * 请求体：{ "pluginIds": ["A_1.0.plugin", "B_1.0.plugin"] }
     * 返回 plugins_<date>.zip。
     */
    @RequestMapping(value = "/plugins/export/batch", method = RequestMethod.POST)
    public ResponseEntity<byte[]> exportPluginsBatch(@RequestBody HashMap<String, Object> params) {
        Object idsObj = params == null ? null : params.get("pluginIds");
        if (!(idsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("pluginIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        List<String> ids = rawList.stream()
                .filter(o -> o instanceof String s && !s.isBlank())
                .map(o -> ((String) o).trim())
                .toList();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("pluginIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        try {
            File pluginDir = new File(LeoConfig.getVfsPath(), PLUGIN_DIR_NAME);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (String pluginId : ids) {
                    String safeFileName;
                    try {
                        safeFileName = getSafeFileName(pluginId);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    File f = new File(pluginDir, safeFileName);
                    if (!f.exists()) continue;
                    zos.putNextEntry(new ZipEntry(safeFileName));
                    zos.write(Files.readAllBytes(f.toPath()));
                    zos.closeEntry();
                }
            }
            String filename = "plugins_" + LocalDate.now() + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, DownloadHeaders.attachmentValue(filename))
                    .body(baos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("批量导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 导入插件：POST /plugins/import（multipart/form-data）
     * 参数：file（.plugin 或 .zip）、conflictPolicy（skip/overwrite，默认 skip）
     * 响应：{ results: [{pluginId, pluginName, status, message}] }
     */
    @RequestMapping(value = "/plugins/import", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> importPlugins(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.badRequest("file 不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        ConflictPolicy policy = ConflictPolicy.parse(conflictPolicy);
        String originalFilename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            List<Map<String, Object>> results;
            byte[] fileBytes = file.getBytes();
            if (originalFilename.endsWith(".zip")) {
                results = importFromZip(fileBytes, policy, user);
            } else if (originalFilename.endsWith(PLUGIN_FILE_EXTENSION)) {
                results = new ArrayList<>();
                results.add(importOnePluginFile(fileBytes, policy, user));
            } else {
                return ApiResponse.badRequest("不支持的文件类型，仅支持 .plugin 或 .zip");
            }
            HashMap<String, Object> data = new HashMap<>();
            data.put("results", results);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    // ── 导入私有辅助方法 ──────────────────────────────────────────────────────

    private List<Map<String, Object>> importFromZip(byte[] zipBytes, ConflictPolicy policy, User user) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        SafeZipReader.forEach(
                new ByteArrayInputStream(zipBytes),
                name -> name.toLowerCase().endsWith(PLUGIN_FILE_EXTENSION),
                SafeZipReader.Limits.DEFAULT,
                (name, bytes) -> results.add(importOnePluginFile(bytes, policy, user))
        );
        return results;
    }

    private Map<String, Object> importOnePluginFile(byte[] pluginBytes, ConflictPolicy policy, User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        Plugin plugin;
        try {
            String decrypted = AesUtil.decrypt(new String(pluginBytes, StandardCharsets.UTF_8), LeoConfig.getPluginEncryptKey());
            plugin = (Plugin) JsonUtil.fromJsonString(decrypted, Plugin.class);
        } catch (Exception e) {
            result.put("pluginId", null);
            result.put("pluginName", null);
            result.put("status", "failed");
            result.put("message", "文件解析失败: " + e.getMessage());
            return result;
        }
        if (plugin.getBytecode() == null || plugin.getBytecode().length == 0) {
            result.put("pluginId", plugin.getPluginId());
            result.put("pluginName", plugin.getPluginName());
            result.put("status", "failed");
            result.put("message", "插件字节码为空");
            return result;
        }
        // 重新派生 pluginId，确保一致性
        try {
            String identifier = isJavaPlugin(plugin.getPluginType())
                    ? DecompilerUtil.extractClassName(plugin.getBytecode())
                    : plugin.getPluginName();
            plugin.setPluginId(generatePluginId(identifier, plugin.getVersion()));
        } catch (Exception e) {
            result.put("pluginId", plugin.getPluginId());
            result.put("pluginName", plugin.getPluginName());
            result.put("status", "failed");
            result.put("message", "字节码验证失败: " + e.getMessage());
            return result;
        }
        String pluginId = plugin.getPluginId();
        boolean exists = pluginManager.getPluginById(pluginId) != null;
        if (exists && policy == ConflictPolicy.SKIP) {
            result.put("pluginId", pluginId);
            result.put("pluginName", plugin.getPluginName());
            result.put("status", "skipped");
            result.put("message", "已存在，已跳过");
            return result;
        }
        try {
            plugin.setCreateUserId(user.getUserId());
            plugin.setCreateTime(String.valueOf(System.currentTimeMillis()));
            pluginManager.installPlugin(plugin);
            savePlugin(plugin);
            result.put("pluginId", pluginId);
            result.put("pluginName", plugin.getPluginName());
            result.put("status", exists ? "overwritten" : "imported");
            result.put("message", exists ? "已覆盖" : "导入成功");
        } catch (Exception e) {
            result.put("pluginId", pluginId);
            result.put("pluginName", plugin.getPluginName());
            result.put("status", "failed");
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 导入冲突策略。当前 Plugin 模块仅支持 SKIP / OVERWRITE；
     * RENAME 暂未实现（pluginId 由字节码派生，重命名语义不直观）。
     */
    public enum ConflictPolicy {
        SKIP, OVERWRITE;

        public static ConflictPolicy parse(String value) {
            if (value == null) return SKIP;
            return switch (value.toLowerCase()) {
                case "overwrite" -> OVERWRITE;
                default          -> SKIP;
            };
        }
    }

    /**
     * 更新插件字段
     */
    private void updatePluginFields(HashMap<String, Object> params, Plugin plugin) {
        if (params.containsKey(PARAM_PLUGIN_NAME)) {
            plugin.setPluginName((String) params.get(PARAM_PLUGIN_NAME));
        }
        if (params.containsKey(PARAM_PLUGIN_DESCRIPTION)) {
            plugin.setPluginDescription((String) params.get(PARAM_PLUGIN_DESCRIPTION));
        }
        if (params.containsKey(PARAM_VERSION)) {
            plugin.setVersion((String) params.get(PARAM_VERSION));
        }
        if (params.containsKey(PARAM_PARAMS_DEMO)) {
            plugin.setParamsDemo((String) params.get(PARAM_PARAMS_DEMO));
        }
        if (params.containsKey(PARAM_PLUGIN_TYPE)) {
            plugin.setPluginType((String) params.get(PARAM_PLUGIN_TYPE));
        }
        if (params.containsKey(PARAM_REMARK)) {
            plugin.setRemark((String) params.get(PARAM_REMARK));
        }
        applyRuntimeMetadata(params, plugin);
    }

    private void applyRuntimeMetadata(Map<String, Object> params, Plugin plugin) {
        if (params.containsKey(PARAM_RUNTIME)) plugin.setRuntime(text(params.get(PARAM_RUNTIME)));
        if (params.containsKey(PARAM_KIND)) plugin.setKind(text(params.get(PARAM_KIND)));
        if (params.containsKey(PARAM_LANGUAGE)) plugin.setLanguage(text(params.get(PARAM_LANGUAGE)));
        if (params.containsKey(PARAM_ENTRYPOINT)) plugin.setEntrypoint(text(params.get(PARAM_ENTRYPOINT)));
        if (params.containsKey(PARAM_PARAMETER_SCHEMA)) {
            plugin.setParameterSchema(text(params.get(PARAM_PARAMETER_SCHEMA)));
        }
        if (params.containsKey(PARAM_RISK_LEVEL)) plugin.setRiskLevel(text(params.get(PARAM_RISK_LEVEL)));
        if (params.containsKey("requirements") && params.get("requirements") instanceof Map<?, ?> raw) {
            Map<String, Object> requirements = new LinkedHashMap<>();
            raw.forEach((key, value) -> requirements.put(String.valueOf(key), value));
            plugin.setRequirements(requirements);
        }
        if (plugin.getRuntime() == null || plugin.getRuntime().isBlank()) {
            throw new IllegalArgumentException("runtime 不能为空");
        }
        if (plugin.getLanguage() == null || plugin.getLanguage().isBlank()) {
            plugin.setLanguage(plugin.getPluginType());
        }
        if (plugin.getKind() == null || plugin.getKind().isBlank()) {
            plugin.setKind(isJavaPlugin(plugin.getPluginType()) ? "bytecode" : "source");
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
    
    /**
     * 如果提供了新的字节码或脚本内容，更新插件 payload 并返回新的 pluginId（如果变化）。
     *
     * <p>java 类型走 bytecode + 反编译校验；脚本类型走 scriptContent UTF-8 文本字节。
     * 两者互斥：传哪个就用哪个，都不传则保持现状。
     */
    private String updateBytecodeIfProvided(HashMap<String, Object> params, Plugin plugin,
                                             String currentPluginId, String safeFileName) throws Exception {
        boolean isJava = isJavaPlugin(plugin.getPluginType());
        byte[] payloadBytes;
        String identifier;
        if (isJava) {
            if (!params.containsKey(PARAM_BYTECODE)) {
                return null;
            }
            Object bytecodeObj = params.get(PARAM_BYTECODE);
            if (!(bytecodeObj instanceof String s) || s.isBlank()) {
                return null;
            }
            try {
                payloadBytes = decodeAndValidateBytecode(s);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            identifier = DecompilerUtil.extractClassName(payloadBytes);
        } else {
            if (!params.containsKey(PARAM_SCRIPT_CONTENT)) {
                return null;
            }
            Object scriptObj = params.get(PARAM_SCRIPT_CONTENT);
            if (!(scriptObj instanceof String s) || s.isBlank()) {
                return null;
            }
            payloadBytes = s.getBytes(StandardCharsets.UTF_8);
            identifier = plugin.getPluginName();
        }
        plugin.setBytecode(payloadBytes);

        // 重新派生 pluginId：基于类名/插件名 + 版本
        String newPluginId = generatePluginId(identifier, plugin.getVersion());

        if (!currentPluginId.equals(newPluginId)) {
            pluginManager.unload(currentPluginId);
            plugin.setPluginId(newPluginId);

            // 删除旧文件
            File root = new File(LeoConfig.getVfsPath());
            File pluginDir = new File(root, PLUGIN_DIR_NAME);
            File oldPluginFile = new File(pluginDir, safeFileName);
            if (oldPluginFile.exists()) {
                oldPluginFile.delete();
            }
            return newPluginId;
        }
        return null;
    }
}
