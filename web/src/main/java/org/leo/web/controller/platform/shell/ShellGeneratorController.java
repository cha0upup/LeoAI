package org.leo.web.controller.platform.shell;

import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.jmg.ServerInjectorMapper;
import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.core.util.ApiResponse;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.service.shell.ShellResultStore;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Shell生成器控制器
 * 提供WebShell和内存马生成的REST API接口
 *
 * @author LeoSpring
 */
@RestController
@RequestMapping("/platform/shell-generator")
public class ShellGeneratorController {

    @Autowired
    private DisguiseManager disguiseManager;

    @Autowired
    private ShellResultStore shellResultStore;

    /**
     * 取回 AI 工具生成的完整代码。
     * AI 工具调用后将代码存入缓存并返回 resultId，
     * 前端凭此端点直接拿到完整内容，避免 LLM 上下文截断问题。
     */
    @RequestMapping(value = "/result/{id}", method = RequestMethod.GET)
    public HashMap<String, Object> getGeneratedResult(@PathVariable("id") String id) {
        String content = shellResultStore.getContent(id);
        if (content == null) {
            return ApiResponse.badRequest("结果不存在或已过期（TTL 30 分钟）: " + id);
        }
        java.util.Map<String, Object> meta = shellResultStore.getMeta(id);
        HashMap<String, Object> data = new HashMap<>();
        data.put("resultId", id);
        data.put("content",  content);
        if (meta != null) data.put("meta", meta);
        return ApiResponse.success(data);
    }

    /**
     * 返回可用的 JSP/JSPX 混淆步骤列表，供前端渲染配置卡片。
     * 每项包含：id、nameZh、description、jspCompatible、jspxCompatible。
     */
    @RequestMapping(value = "/obfuscation-steps", method = RequestMethod.GET)
    public HashMap<String, Object> getObfuscationSteps() {
        return ApiResponse.success(JspObfuscationPipeline.getStepDescriptors());
    }

    /**
     * 返回应用服务器类型与支持的注入器形态、以及打包器类型（含分组层级）。
     * data：serverInjectorTypes、packerTypes（见 {@link ServerInjectorMapper#getSupportedPackerTypesHierarchy()}）。
     */
    @RequestMapping(value = "/supported-types", method = RequestMethod.GET)
    public HashMap<String, Object> getSupportedTypes() {
        HashMap<String, Object> result = new HashMap<>();

        result.put("serverInjectorTypes", ServerInjectorMapper.getAllServerInjectorMapAsString());

        result.put("packerTypes", ServerInjectorMapper.getSupportedPackerTypesHierarchy());

        // 每个 packer 声明的混淆步骤 ID 列表（空列表表示不支持混淆层配置）
        result.put("packerObfuscationSteps", PackerRegistry.getPackerObfuscationStepsMap());

        return ApiResponse.success(result);
    }

    /**
     * 生成JSP或JSPX格式的WebShell
     * 通过shellType参数区分：JSP 或 JSPX
     */
    @RequestMapping(value = "/generate/webshell", method = RequestMethod.POST)
    public HashMap<String, Object> generateWebShell(@RequestBody HashMap<String, Object> params) {
        try {


            // 获取必需参数
            String reqDisguiseId = ControllerUtil.getRequiredStringParam(params, "reqDisguiseId");
            String respDisguiseId = ControllerUtil.getRequiredStringParam(params, "respDisguiseId");
            String shellTypeStr = ControllerUtil.getRequiredStringParam(params, "shellType");

            // 验证Shell类型（直接使用字符串，不依赖枚举）
            if (shellTypeStr == null || shellTypeStr.isBlank()) {
                return ApiResponse.badRequest("shellType参数不能为空");
            }
            String shellTypeUpper = shellTypeStr.toUpperCase();
            if (!"JSP".equals(shellTypeUpper) && !"JSPX".equals(shellTypeUpper)) {
                return ApiResponse.badRequest("shellType参数必须是JSP或JSPX，当前值: " + shellTypeStr);
            }

            // 获取Disguise对象
            Disguise reqDisguise = disguiseManager.getDisguiseById(reqDisguiseId);
            if (reqDisguise == null) {
                return ApiResponse.badRequest("请求伪装器不存在: " + reqDisguiseId);
            }

            Disguise respDisguise = disguiseManager.getDisguiseById(respDisguiseId);
            if (respDisguise == null) {
                return ApiResponse.badRequest("响应伪装器不存在: " + respDisguiseId);
            }

            // 获取可选参数
            String coreClassName = ControllerUtil.getOptionalStringParam(params, "coreClassName");
            String protocol = ControllerUtil.getOptionalStringParam(params, "protocol");
            Integer respCode = getOptionalIntegerParam(params, "respCode");
            if (respCode == null) {
                respCode = 200;
            }

            // 构建配置
            ShellGeneratorConfig.Builder configBuilder = ShellGeneratorConfig.builder(reqDisguise, respDisguise)
                    .respCode(respCode);

            // 设置传输协议（如果提供）
            if (protocol != null && !protocol.isBlank()) {
                configBuilder.protocol(protocol);
            }

            if (coreClassName != null && !coreClassName.isBlank()) {
                configBuilder.coreClassName(coreClassName);
            }

            List<String> jspObfuscationSteps = getOptionalStringListParam(params, "jspObfuscationSteps");
            // null = 字段未发送（使用默认预设）；空列表 = 用户主动禁用全部，也需设置
            if (jspObfuscationSteps != null) {
                configBuilder.jspObfuscationSteps(jspObfuscationSteps);
            }

            ShellGeneratorConfig config = configBuilder.build();
            ShellGenerator generator = new ShellGenerator(config);

            // 根据类型生成Shell
            String shell;
            if ("JSP".equals(shellTypeUpper)) {
                shell = generator.generateJspShell();
            } else {
                shell = generator.generateJspxShell();
            }

            HashMap<String, Object> data = new HashMap<>();
            data.put("shell", shell);
            data.put("type", shellTypeUpper);
            data.put("coreClassName", config.getCoreClassName());
            data.put("protocol", config.getProtocol());

            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("生成WebShell失败: " + e.getMessage());
        }
    }

    /**
     * 生成内存马：LeoCore → Shell 模板 → 注入器类字节码 → {@link org.leo.jmg.mem.packer.Packer} 输出字符串。
     * 必填：serverType、shellType、packerType。
     */
    @RequestMapping(value = "/generate/memoryshell", method = RequestMethod.POST)
    public HashMap<String, Object> generateMemoryShell(@RequestBody HashMap<String, Object> params) {
        try {


            // 获取必需参数
            String reqDisguiseId = ControllerUtil.getRequiredStringParam(params, "reqDisguiseId");
            String respDisguiseId = ControllerUtil.getRequiredStringParam(params, "respDisguiseId");
            String headerName = ControllerUtil.getRequiredStringParam(params, "headerName");
            String headerValue = ControllerUtil.getRequiredStringParam(params, "headerValue");
            String serverType = firstNonBlankParam(params, "serverType");
            if (serverType == null) {
                return ApiResponse.badRequest("serverType 不能为空");
            }
            String shellType = firstNonBlankParam(params, "shellType");
            if (shellType == null) {
                return ApiResponse.badRequest("shellType 不能为空");
            }
            String packerType = firstNonBlankParam(params, "packerType");
            if (packerType == null) {
                return ApiResponse.badRequest("packerType 不能为空");
            }

            // 获取Disguise对象
            Disguise reqDisguise = disguiseManager.getDisguiseById(reqDisguiseId);
            if (reqDisguise == null) {
                return ApiResponse.badRequest("请求伪装器不存在: " + reqDisguiseId);
            }

            Disguise respDisguise = disguiseManager.getDisguiseById(respDisguiseId);
            if (respDisguise == null) {
                return ApiResponse.badRequest("响应伪装器不存在: " + respDisguiseId);
            }


            // 获取可选参数
            String coreClassName = ControllerUtil.getOptionalStringParam(params, "coreClassName");
            String injectorClassName = ControllerUtil.getOptionalStringParam(params, "injectorClassName");
            String shellClassName = ControllerUtil.getOptionalStringParam(params, "shellClassName");
            String urlPattern = ControllerUtil.getOptionalStringParam(params, "urlPattern");
            if (urlPattern == null || urlPattern.isBlank()) {
                urlPattern = "/*";
            }

            Boolean isAbstractTranslet = getOptionalBooleanParam(params, "isAbstractTranslet");
            if (isAbstractTranslet == null) {
                isAbstractTranslet = false;
            }

            Integer respCode = getOptionalIntegerParam(params, "respCode");
            if (respCode == null) {
                respCode = 200;
            }

            // 构建配置
            ShellGeneratorConfig.Builder configBuilder = ShellGeneratorConfig.builder(reqDisguise, respDisguise)
                    .header(headerName, headerValue)
                    .serverType(serverType)
                    .shellType(shellType)
                    .packerType(packerType)
                    .urlPattern(urlPattern)
                    .abstractTranslet(isAbstractTranslet)
                    .respCode(respCode);

            Boolean byPassJavaModule = getOptionalBooleanParam(params, "byPassJavaModule");
            if (byPassJavaModule != null) {
                configBuilder.byPassJavaModule(byPassJavaModule);
            }

            List<String> jspObfuscationSteps = getOptionalStringListParam(params, "jspObfuscationSteps");
            // null = 字段未发送（使用默认预设）；空列表 = 用户主动禁用全部，也需设置
            if (jspObfuscationSteps != null) {
                configBuilder.jspObfuscationSteps(jspObfuscationSteps);
            }

            if (coreClassName != null && !coreClassName.isBlank()) {
                configBuilder.coreClassName(coreClassName);
            }else {
                configBuilder.coreClassName(ClassNameGenerator.generateServletStyleClassName());
            }
            if (injectorClassName != null && !injectorClassName.isBlank()) {
                configBuilder.injectorClassName(injectorClassName);
            }else {
                configBuilder.injectorClassName(ClassNameGenerator.generateServletStyleClassName());
            }
            if (shellClassName != null && !shellClassName.isBlank()) {
                configBuilder.shellClassName(shellClassName);
            }else {
                configBuilder.shellClassName(ClassNameGenerator.generateServletStyleClassName());
            }

            ShellGeneratorConfig config = configBuilder.build();
            ShellGenerator generator = new ShellGenerator(config);

            String packed = generator.generateFormattedInjector();

            HashMap<String, Object> data = new HashMap<>();
            data.put("code", packed);
            data.put("packerType", config.getPackerType());
            data.put("shellType", config.getShellType());
            data.put("serverType", config.getServerType());
            data.put("coreClassName", config.getCoreClassName());
            data.put("injectorClassName", config.getInjectorClassName());
            data.put("shellClassName", config.getShellClassName());
            data.put("urlPattern", config.getUrlPattern());
            data.put("isAbstractTranslet", config.isAbstractTranslet());
            data.put("byPassJavaModule", config.isByPassJavaModule());
            data.put("headerConfig",headerName+" : "+headerValue);

            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("生成内存马失败: " + e.getMessage());
        }
    }

    /**
     * 依次读取多个键，返回第一个非空字符串（trim 后）；均无则返回 null
     */
    private static String firstNonBlankParam(HashMap<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String v = ControllerUtil.getOptionalStringParam(params, key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    // 辅助方法：获取可选的Integer参数
    private Integer getOptionalIntegerParam(HashMap<String, Object> params, String paramName) {
        if (params == null) {
            return null;
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null) {
            return null;
        }
        if (paramObj instanceof Integer) {
            return (Integer) paramObj;
        }
        if (paramObj instanceof Number) {
            return ((Number) paramObj).intValue();
        }
        try {
            return Integer.parseInt(paramObj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 辅助方法：获取可选的 List<String> 参数（前端传 JSON 数组）
    @SuppressWarnings("unchecked")
    private List<String> getOptionalStringListParam(HashMap<String, Object> params, String paramName) {
        if (params == null) return null;
        Object obj = params.get(paramName);
        if (obj == null) return null;
        if (obj instanceof List) {
            List<?> raw = (List<?>) obj;
            List<String> result = new ArrayList<String>(raw.size());
            for (Object item : raw) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return null;
    }

    // 辅助方法：获取可选的Boolean参数
    private Boolean getOptionalBooleanParam(HashMap<String, Object> params, String paramName) {
        if (params == null) {
            return null;
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null) {
            return null;
        }
        if (paramObj instanceof Boolean) {
            return (Boolean) paramObj;
        }
        String str = paramObj.toString().toLowerCase();
        if ("true".equals(str) || "1".equals(str)) {
            return true;
        }
        if ("false".equals(str) || "0".equals(str)) {
            return false;
        }
        return null;
    }
}
