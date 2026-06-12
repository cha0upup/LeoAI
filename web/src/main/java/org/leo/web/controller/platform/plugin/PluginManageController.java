package org.leo.web.controller.platform.plugin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Plugin;
import org.leo.core.entity.User;
import org.leo.core.manager.PluginManager;
import org.leo.core.util.decompiler.DecompilerUtil;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import org.leo.core.util.aes.AesUtil;

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
    private static final String PARAM_PARAMS_DEMO = "paramsDemo";
    private static final String PARAM_PLUGIN_TYPE = "pluginType";
    private static final String PARAM_PLUGIN_ID = "pluginId";
    private static final String PARAM_REMARK = "remark";
    
    // 结果字段常量
    private static final String RESULT_PLUGIN_ID = "pluginId";
    private static final String RESULT_PLUGIN_NAME = "pluginName";
    private static final String RESULT_JAVA_CODE = "javaCode";
    
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
    public HashMap<String, Object> addPlugin(@RequestBody HashMap<String, Object> params, HttpServletRequest request) throws Exception {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        Plugin componentPlugin = new Plugin();
        componentPlugin.setPluginName((String) params.get(PARAM_PLUGIN_NAME));
        componentPlugin.setPluginDescription((String) params.get(PARAM_PLUGIN_DESCRIPTION));
        componentPlugin.setVersion((String) params.get(PARAM_VERSION));

        Object bytecodeObj = params.get(PARAM_BYTECODE);
        if (bytecodeObj == null || !(bytecodeObj instanceof String)) {
            return ApiResponse.badRequest("bytecode参数不能为空且必须是Base64字符串");
        }
        byte[] bytecode = Base64.getDecoder().decode((String) bytecodeObj);
        try {
            DecompilerUtil.decompile(bytecode);
        } catch (Exception e) {
            return ApiResponse.badRequest("字节码验证失败");
        }
        componentPlugin.setBytecode(bytecode);
        componentPlugin.setParamsDemo((String) params.get(PARAM_PARAMS_DEMO));
        componentPlugin.setPluginType((String) params.get(PARAM_PLUGIN_TYPE));
        
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        componentPlugin.setCreateUserId(user.getUserId());
        componentPlugin.setCreateTime(String.valueOf(System.currentTimeMillis()));
        
        // 从bytecode中提取类名（简单类名，不包含包名），生成插件ID格式：类名_版本号.plugin
        String className = DecompilerUtil.extractClassName(bytecode);
        String pluginId = generatePluginId(className, componentPlugin.getVersion());
        componentPlugin.setPluginId(pluginId);
        pluginManager.inStallPlugin(componentPlugin);
        boolean result = savePlugin(componentPlugin);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("保存插件失败");
        }
    }

    private boolean savePlugin(Plugin componentPlugin) throws Exception {
        if (componentPlugin == null || componentPlugin.getPluginId() == null) {
            throw new IllegalArgumentException("componentPlugin或pluginId不能为空");
        }
        File root = new File(LeoConfig.getVfsPath());
        File plugin = new File(root, PLUGIN_DIR_NAME);
        if (!plugin.exists()) {
            plugin.mkdirs();
        }
        // 文件名直接使用插件ID（格式：类名_版本号.plugin）
        String pluginId = componentPlugin.getPluginId();
        // 安全检查：确保文件名安全，防止路径遍历攻击
        String safeName = getSafeFileName(pluginId);
        File pluginFile = new File(plugin, safeName);
        try (FileOutputStream fileOutputStream = new FileOutputStream(pluginFile)) {
            String encrypted = AesUtil.encrypt(componentPlugin.toString(), LeoConfig.getPluginEncryptKey());
            fileOutputStream.write(encrypted.getBytes(StandardCharsets.UTF_8));
            fileOutputStream.flush();
        }
        return true;
    }
    @RequestMapping(value = "/plugins/delete", method = RequestMethod.POST)
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
        boolean saved = savePlugin(existingPlugin);
        if (saved) {
            // 重新加载插件到PluginManager
            pluginManager.inStallPlugin(existingPlugin);
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put(RESULT_PLUGIN_ID, existingPlugin.getPluginId());
            return ApiResponse.success("插件更新成功", data);
        } else {
            return ApiResponse.error("插件保存失败");
        }
    }

    @RequestMapping(value = "/plugins/upload", method = RequestMethod.POST)
    public HashMap<String, Object> uploadPlugin(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        
        if (file == null || file.isEmpty()) {
            return ApiResponse.badRequest("文件不能为空");
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            return ApiResponse.badRequest("文件名不能为空");
        }
        
        String fileName = originalFilename.toLowerCase();
        byte[] fileBytes = file.getBytes();
        
        // 检查文件类型，只允许.plugin文件
        if (!fileName.endsWith(PLUGIN_FILE_EXTENSION)) {
            return ApiResponse.badRequest("不支持的文件类型，仅支持 .plugin 文件");
        }
        
        // 检查用户登录
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        
        Plugin componentPlugin;
        
        // 处理 .plugin 文件（已加密的插件文件）
        try {
            // 尝试解密并解析插件文件
            String decrypted = AesUtil.decrypt(new String(fileBytes, StandardCharsets.UTF_8), LeoConfig.getPluginEncryptKey());
            componentPlugin = (Plugin) JsonUtil.fromJsonString(decrypted, Plugin.class);
            
            // 所有插件信息都从插件文件中解析，不允许通过参数覆盖
            // 更新创建信息
            componentPlugin.setCreateUserId(user.getUserId());
            componentPlugin.setCreateTime(String.valueOf(System.currentTimeMillis()));
            
            // 如果版本变化，重新生成pluginId
            String className = DecompilerUtil.extractClassName(componentPlugin.getBytecode());
            String pluginId = generatePluginId(className, componentPlugin.getVersion());
            componentPlugin.setPluginId(pluginId);
            
        } catch (Exception e) {
            return ApiResponse.badRequest("插件文件解析失败: " + e.getMessage());
        }
        
        // 验证插件信息完整性
        if (componentPlugin.getBytecode() == null || componentPlugin.getBytecode().length == 0) {
            return ApiResponse.badRequest("插件字节码不能为空");
        }
        
        // 安装并保存插件
        pluginManager.inStallPlugin(componentPlugin);
        boolean saved = savePlugin(componentPlugin);
        
        if (saved) {
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put(RESULT_PLUGIN_ID, componentPlugin.getPluginId());
            data.put(RESULT_PLUGIN_NAME, componentPlugin.getPluginName());
            return ApiResponse.success("插件上传成功", data);
        } else {
            return ApiResponse.error("插件保存失败");
        }
    }

    @RequestMapping(value = "/plugins", method = RequestMethod.GET)
    public HashMap<String, Object> getPlugin() {
        ArrayList<Plugin> plugins = (ArrayList<Plugin>) pluginManager.getPluginAsList();
        return ApiResponse.success(plugins);
    }
    
    @RequestMapping(value = "/plugins/by-type", method = RequestMethod.POST)
    public HashMap<String, Object> getPluginByType(@RequestBody HashMap<String, Object> params) throws Exception {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        String type = (String) params.get("pluginType");
        if (type == null) {
            return ApiResponse.badRequest("pluginType不能为空");
        }
        ArrayList<Plugin> plugins = (ArrayList<Plugin>) pluginManager.getPluginAsListByType(type);
        return ApiResponse.success(plugins);
    }

    @RequestMapping(value = "/decompile", method = RequestMethod.POST)
    public HashMap<String, Object> decompile(@RequestBody HashMap<String, Object> params) throws IOException {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        Object bytecodeObj = params.get("bytecode");
        if (bytecodeObj == null || !(bytecodeObj instanceof String)) {
            return ApiResponse.badRequest("bytecode参数不能为空且必须是Base64字符串");
        }
        byte[] bytecode = Base64.getDecoder().decode((String) bytecodeObj);
        String decompiledCode = DecompilerUtil.decompile(bytecode);
        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put(RESULT_JAVA_CODE, decompiledCode);
        return ApiResponse.success(data);
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
    }
    
    /**
     * 如果提供了新的字节码，更新字节码并返回新的pluginId（如果变化）
     */
    private String updateBytecodeIfProvided(HashMap<String, Object> params, Plugin plugin, 
                                             String currentPluginId, String safeFileName) throws Exception {
        if (!params.containsKey(PARAM_BYTECODE)) {
            return null;
        }
        
        Object bytecodeObj = params.get(PARAM_BYTECODE);
        if (bytecodeObj == null || !(bytecodeObj instanceof String)) {
            return null;
        }
        
        byte[] bytecode = Base64.getDecoder().decode((String) bytecodeObj);
        try {
            DecompilerUtil.decompile(bytecode);
        } catch (Exception e) {
            throw new RuntimeException("字节码验证失败: " + e.getMessage(), e);
        }
        plugin.setBytecode(bytecode);
        
        // 如果更新了字节码，可能需要重新生成pluginId（基于新的类名和版本）
        String className = DecompilerUtil.extractClassName(bytecode);
        String newPluginId = generatePluginId(className, plugin.getVersion());
        
        // 如果pluginId发生变化，需要先卸载旧的，再安装新的
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
