package org.leo.web.controller.platform.shell;

import org.leo.core.entity.Disguise;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.catalog.GeneratorCatalog;
import org.leo.jmg.generation.MemoryShellGenerationCommand;
import org.leo.jmg.generation.ShellGenerationOutcome;
import org.leo.jmg.generation.ShellGenerationService;
import org.leo.jmg.generation.WebShellGenerationCommand;
import org.leo.core.util.ApiResponse;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.jsp.JspObfuscationStepCatalog;
import org.leo.service.shell.ShellResultStore;
import org.leo.service.generator.ScriptGeneratorService;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shell生成器控制器
 * 提供WebShell和内存马生成的REST API接口
 *
 * @author LeoSpring
 */
@RestController
@RequestMapping("/platform/shell-generator")
public class ShellGeneratorController {

    private static final ShellGenerationService SHELL_GENERATION_SERVICE =
            new ShellGenerationService();

    @Autowired
    private DisguiseManager disguiseManager;

    @Autowired
    private ShellResultStore shellResultStore;

    @Autowired
    private ScriptGeneratorService scriptGeneratorService;

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
        return ApiResponse.success(JspObfuscationStepCatalog.getDescriptors());
    }

    /**
     * 返回应用服务器类型与支持的注入器形态、以及打包器类型（含分组层级）。
     * data：serverInjectorTypes、packerTypes。
     */
    @RequestMapping(value = "/supported-types", method = RequestMethod.GET)
    public HashMap<String, Object> getSupportedTypes() {
        HashMap<String, Object> result = new HashMap<>();

        result.put("serverInjectorTypes", GeneratorCatalog.getServerInjectorMap());
        // 协议维度映射：{http/httpchunk/websocket: {serverType: [injectorName]}}
        // 供前端按当前协议直接取可用服务器与注入器，避免靠名字硬匹配 HTTPCHUNK 别名
        result.put("serverProtocolInjectorTypes", GeneratorCatalog.getProtocolInjectorMap());
        result.put("injectorCapabilities", GeneratorCatalog.getCapabilityDescriptors());

        result.put("packerTypes", PackerRegistry.getHierarchy());

        // 每个 packer 声明的混淆步骤 ID 列表（空列表表示不支持混淆层配置）
        result.put("packerObfuscationSteps", PackerRegistry.getPackerObfuscationStepsMap());
        result.put("packerCompatibility", PackerRegistry.getCompatibilityMap());
        result.put("packerAvailability", PackerRegistry.getAvailabilityMap());
        result.put("targetJavaVersions", getTargetJavaVersions());
        result.put("servletNamespaces", ServletNamespace.valuesAsStrings());
        HashMap<String, Object> transportProtocols = new HashMap<>();
        transportProtocols.put("webshell", ShellGeneratorConfig.getSupportedWebShellProtocols());
        transportProtocols.put("memoryshell", ShellGeneratorConfig.getSupportedMemoryShellProtocols());
        result.put("transportProtocols", transportProtocols);
        result.put("runtimeGenerators", scriptGeneratorService.getMetadata());
        result.put("memoryShellBuildOptions", getMemoryShellBuildOptions());

        return ApiResponse.success(result);
    }

    /** Generate an artifact through a runtime-specific provider such as phpcore. */
    @RequestMapping(value = "/generate/runtime", method = RequestMethod.POST)
    public HashMap<String, Object> generateRuntimeArtifact(@RequestBody HashMap<String, Object> params) {
        try {
            PuppetRuntime runtime = PuppetRuntime.from(ControllerUtil.getRequiredStringParam(params, "runtime"));
            if (runtime == PuppetRuntime.UNKNOWN) {
                return ApiResponse.badRequest("runtime参数无效");
            }
            if (runtime == PuppetRuntime.PHP) {
                String payloadKey = ControllerUtil.getOptionalStringParam(params, "payloadKey");
                if (payloadKey == null || payloadKey.trim().isEmpty()) {
                    return ApiResponse.badRequest("PHP PayloadCodec AES 密钥不能为空");
                }
            }
            String artifactType = ControllerUtil.getRequiredStringParam(params, "artifactType");
            String reqDisguiseId = ControllerUtil.getRequiredStringParam(params, "reqDisguiseId");
            String respDisguiseId = ControllerUtil.getRequiredStringParam(params, "respDisguiseId");
            Disguise requestDisguise = disguiseManager.getDisguiseById(reqDisguiseId);
            Disguise responseDisguise = disguiseManager.getDisguiseById(respDisguiseId);
            if (requestDisguise == null) return ApiResponse.badRequest("请求伪装器不存在: " + reqDisguiseId);
            if (responseDisguise == null) return ApiResponse.badRequest("响应伪装器不存在: " + respDisguiseId);

            Map<String, Object> options = new LinkedHashMap<>(params);
            for (String key : List.of("runtime", "artifactType", "reqDisguiseId", "respDisguiseId")) {
                options.remove(key);
            }
            GeneratedArtifact artifact = scriptGeneratorService.generate(new GenerationRequest(
                    runtime, artifactType, requestDisguise, responseDisguise, options));

            HashMap<String, Object> data = new HashMap<>();
            data.put("content", artifact.getContent());
            data.put("fileExtension", artifact.getFileExtension());
            data.put("mediaType", artifact.getMediaType());
            data.put("metadata", artifact.getMetadata());
            data.put("warnings", artifact.getWarnings());
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("生成运行时脚本失败: " + e.getMessage());
        }
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
            String targetJavaVersion = ControllerUtil.getOptionalStringParam(params, "targetJavaVersion");
            String servletNamespace = ControllerUtil.getOptionalStringParam(params, "servletNamespace");
            String payloadKey = ControllerUtil.getOptionalStringParam(params, "payloadKey");
            Long obfuscationSeed = getOptionalLongParam(params, "obfuscationSeed");
            Integer respCode = getOptionalIntegerParam(params, "respCode");
            List<String> jspObfuscationSteps = getOptionalStringListParam(params, "jspObfuscationSteps");
            WebShellGenerationCommand command =
                    WebShellGenerationCommand.builder(
                                    reqDisguise, respDisguise, shellTypeStr)
                            .coreClassName(coreClassName)
                            .protocol(protocol)
                            .targetJavaVersion(targetJavaVersion)
                            .servletNamespace(servletNamespace)
                            .payloadKey(payloadKey)
                            .responseCode(respCode)
                            .obfuscationSteps(jspObfuscationSteps)
                            .obfuscationSeed(obfuscationSeed)
                            .build();
            ShellGenerationOutcome outcome =
                    SHELL_GENERATION_SERVICE.generateWebShell(command);

            HashMap<String, Object> data =
                    new HashMap<String, Object>(outcome.getMetadata());
            data.put("shell", outcome.getContent());
            data.put("classArtifacts", outcome.getClassArtifacts());

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
            String protocol = ControllerUtil.getOptionalStringParam(params, "protocol");
            String serverVersion = ControllerUtil.getOptionalStringParam(params, "serverVersion");
            String headerName = ControllerUtil.getOptionalStringParam(params, "headerName");
            String headerValue = ControllerUtil.getOptionalStringParam(params, "headerValue");

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

            Boolean isAbstractTranslet = getOptionalBooleanParam(params, "isAbstractTranslet");
            Integer respCode = getOptionalIntegerParam(params, "respCode");
            Boolean byPassJavaModule = getOptionalBooleanParam(params, "byPassJavaModule");
            Boolean lambdaSuffix = getOptionalBooleanParam(params, "lambdaSuffix");
            Boolean staticInitialize = getOptionalBooleanParam(params, "staticInitialize");
            Boolean shrink = getOptionalBooleanParam(params, "shrink");
            String targetJavaVersion = ControllerUtil.getOptionalStringParam(params, "targetJavaVersion");
            String servletNamespace = ControllerUtil.getOptionalStringParam(params, "servletNamespace");
            Long obfuscationSeed = getOptionalLongParam(params, "obfuscationSeed");
            List<String> jspObfuscationSteps = getOptionalStringListParam(params, "jspObfuscationSteps");
            String customJspTemplate =
                    ControllerUtil.getOptionalStringParam(params, "customJspTemplate");
            String payloadKey = ControllerUtil.getOptionalStringParam(params, "payloadKey");

            MemoryShellGenerationCommand command =
                    MemoryShellGenerationCommand.builder(reqDisguise, respDisguise)
                            .header(headerName, headerValue)
                            .serverType(serverType)
                            .serverVersion(serverVersion)
                            .injectorName(shellType)
                            .packerType(packerType)
                            .protocol(protocol)
                            .targetJavaVersion(targetJavaVersion)
                            .servletNamespace(servletNamespace)
                            .payloadKey(payloadKey)
                            .urlPattern(urlPattern)
                            .coreClassName(coreClassName)
                            .injectorClassName(injectorClassName)
                            .shellClassName(shellClassName)
                            .abstractTranslet(isAbstractTranslet)
                            .bypassJavaModule(byPassJavaModule)
                            .lambdaSuffix(lambdaSuffix)
                            .staticInitialize(staticInitialize)
                            .shrink(shrink)
                            .responseCode(respCode)
                            .obfuscationSteps(jspObfuscationSteps)
                            .customJspTemplate(customJspTemplate)
                            .obfuscationSeed(obfuscationSeed)
                            .build();
            ShellGenerationOutcome outcome =
                    SHELL_GENERATION_SERVICE.generateMemoryShell(command);

            HashMap<String, Object> data =
                    new HashMap<String, Object>(outcome.getMetadata());
            data.put("code", outcome.getContent());
            data.put("classArtifacts", outcome.getClassArtifacts());

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

    private Long getOptionalLongParam(HashMap<String, Object> params, String paramName) {
        if (params == null) {
            return null;
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null) {
            return null;
        }
        if (paramObj instanceof Number) {
            return ((Number) paramObj).longValue();
        }
        try {
            return Long.parseLong(paramObj.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(paramName + " 必须是 64 位整数");
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

    private static List<String> getTargetJavaVersions() {
        List<String> versions = new ArrayList<String>();
        for (TargetJavaVersion version : TargetJavaVersion.values()) {
            versions.add(version.getValue());
        }
        return versions;
    }

    private static Map<String, Object> getMemoryShellBuildOptions() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("lambdaSuffix", option(false,
                "为 Shell 与 Injector 类名追加 $Proxy0$$Lambda$1"));
        options.put("staticInitialize", option(false,
                "在 Injector 的类初始化阶段自动调用无参构造器"));
        options.put("shrink", option(true,
                "移除调试属性、注解与单文件装载不需要的类元数据"));
        options.put("byPassJavaModule", option(false,
                "JDK 9+ 自动启用，并在 Injector 构造入口安装模块兼容逻辑"));
        return java.util.Collections.unmodifiableMap(options);
    }

    private static Map<String, Object> option(boolean defaultValue,
                                              String description) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("defaultValue", defaultValue);
        descriptor.put("description", description);
        return java.util.Collections.unmodifiableMap(descriptor);
    }
}
