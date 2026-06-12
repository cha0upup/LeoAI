package org.leo.jmg.mem.packer.jsp;

import org.leo.jmg.mem.packer.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSP/JSPX 混淆流水线，将多个 {@link JspObfuscationStep} 串联执行。
 *
 * <h3>架构说明</h3>
 * <p>
 * 混淆分为两个阶段：
 * <ol>
 *   <li><b>结构性混淆</b>（渲染阶段，由 {@code TemplateRenderer} 完成）：
 *       变量名/内部类名随机化，通过模板占位符 {@code {{VAR:name}}} / {@code {{CLS:Name}}} 实现，
 *       无需正则后处理，新增模板无需修改任何 Java 代码。</li>
 *   <li><b>展示性混淆</b>（本 pipeline 完成）：
 *       字符串编码、噪声注入、Unicode 转义、HTML 壳包裹等，对已渲染的字符串代码做后处理。</li>
 * </ol>
 *
 * <h3>内置步骤（可直接引用）</h3>
 * <ul>
 *   <li>{@link #SPLIT_STRING_LITERALS}  — 敏感字符串字面量随机拆分</li>
 *   <li>{@link #CHUNK_PAYLOAD}          — base64 payload 随机分块</li>
 *   <li>{@link #GHOST_BITS_ENCODE}      — Ghost Bits（Cast Attack）CJK 编码</li>
 *   <li>{@link #INJECT_SCRIPTLET_NOISE} — 在 scriptlet 块内注入随机无副作用语句</li>
 *   <li>{@link #INSERT_SCRIPT_NOISE}    — 在 scriptlet 边界插入噪声标签（仅 JSP）</li>
 *   <li>{@link #UNICODE_ENCODE_JSP}     — Unicode 多 u 转义编码（JSP 模式）</li>
 *   <li>{@link #UNICODE_ENCODE_JSPX}    — Unicode 多 u 转义编码（JSPX 模式）</li>
 *   <li>{@link #WRAP_HTML_JS}           — 外层 HTML + jQuery 注释壳包裹（仅 JSP）</li>
 *   <li>{@link #IDENTIFIER_RENAME}      — 特征变量名重命名（classBytes/unsafe/clazz 等）</li>
 *   <li>{@link #XOR_PAYLOAD_ENCODE}     — base64 payload 随机 XOR 扰动，破坏 hash 指纹</li>
 *   <li>{@link #PACK_PAYLOAD}           — base64 payload 双字符打包，消灭 base64 字符集特征</li>
 *   <li>{@link #DEAD_BLOCK_INJECT}      — 注入引用 Spring/JNDI/Servlet API 的死代码块</li>
 *   <li>{@link #BYTE_ARRAY_ENCODE}      — 敏感字符串替换为 new String(new byte[]{...})</li>
 *   <li>{@link #PACK_TWO_TO_ONE}        — 两个相邻 ASCII 字符打包为一个 char，字符串长度减半</li>
 * </ul>
 *
 * <h3>预置组合</h3>
 * <ul>
 *   <li>{@link #jspDefault()}     — 标准 JSP：字符串拆分 + 分块 + scriptlet 噪声 + 边界噪声</li>
 *   <li>{@link #jspUnicode()}     — JSP Unicode：默认 + Unicode 编码</li>
 *   <li>{@link #jspHtmlWrapped()} — JSP HTML 壳：默认 + HTML 包裹</li>
 *   <li>{@link #jspFullStealth()} — JSP 全量：默认 + Unicode + HTML 壳</li>
 *   <li>{@link #jspxDefault()}    — 标准 JSPX：字符串拆分 + 分块 + scriptlet 噪声</li>
 *   <li>{@link #jspxUnicode()}    — JSPX Unicode：默认 + Unicode 编码</li>
 * </ul>
 *
 * <h3>自定义示例</h3>
 * <pre>{@code
 * JspObfuscationPipeline pipeline = JspObfuscationPipeline.builder()
 *     .add(JspObfuscationPipeline.SPLIT_STRING_LITERALS)
 *     .add(JspObfuscationPipeline.CHUNK_PAYLOAD)
 *     .add(JspObfuscationPipeline.UNICODE_ENCODE_JSP)
 *     .build();
 * String result = pipeline.apply(rawCode);
 * }</pre>
 */
public final class JspObfuscationPipeline {

    // -------------------------------------------------------------------------
    // 步骤元数据描述（供前端展示与配置使用）
    // -------------------------------------------------------------------------

    /** 单个混淆步骤的描述信息，序列化为 JSON 返回给前端。 */
    public static class StepDescriptor {
        private final String id;
        private final String nameZh;
        private final String description;
        private final boolean jspCompatible;
        private final boolean jspxCompatible;
        /**
         * 是否可安全用于 WebShell（即不改变 HTTP 响应内容）。
         * WRAP_HTML_JS 会在响应体中混入 HTML，导致客户端解析失败，故为 false。
         * 其余步骤仅变换 Java 语法层，不影响运行时输出，均为 true。
         */
        private final boolean webshellCompatible;
        /**
         * 与本步骤互斥的步骤 ID 集合（同时出现时记录 warning，后者被跳过）。
         * 例如 GHOST_BITS_ENCODE 与 SPLIT_STRING_LITERALS 同时选时，两者都针对同一组字符串，
         * 先执行的有效，后执行的是空操作。
         */
        private final java.util.Set<String> incompatibleWith;
        /**
         * 本步骤必须排在这些步骤 ID 之前（若顺序颠倒则记录 warning）。
         * 例如 INJECT_SCRIPTLET_NOISE 必须在 UNICODE_ENCODE_* 之前，
         * 否则注入的语句已被 Unicode 编码，可读性丧失且噪声效果下降。
         */
        private final java.util.Set<String> mustPrecede;

        public StepDescriptor(String id, String nameZh, String description,
                              boolean jspCompatible, boolean jspxCompatible,
                              boolean webshellCompatible,
                              String[] incompatibleWith, String[] mustPrecede) {
            this.id = id;
            this.nameZh = nameZh;
            this.description = description;
            this.jspCompatible = jspCompatible;
            this.jspxCompatible = jspxCompatible;
            this.webshellCompatible = webshellCompatible;
            this.incompatibleWith = incompatibleWith.length == 0
                    ? java.util.Collections.emptySet()
                    : new java.util.HashSet<String>(java.util.Arrays.asList(incompatibleWith));
            this.mustPrecede = mustPrecede.length == 0
                    ? java.util.Collections.emptySet()
                    : new java.util.HashSet<String>(java.util.Arrays.asList(mustPrecede));
        }

        /** 兼容旧调用（无约束字段）的构造器 */
        public StepDescriptor(String id, String nameZh, String description,
                              boolean jspCompatible, boolean jspxCompatible,
                              boolean webshellCompatible) {
            this(id, nameZh, description, jspCompatible, jspxCompatible, webshellCompatible,
                    new String[0], new String[0]);
        }

        public String getId()               { return id; }
        public String getNameZh()           { return nameZh; }
        public String getDescription()      { return description; }
        public boolean isJspCompatible()    { return jspCompatible; }
        public boolean isJspxCompatible()   { return jspxCompatible; }
        public boolean isWebshellCompatible() { return webshellCompatible; }
        public java.util.Set<String> getIncompatibleWith() { return incompatibleWith; }
        public java.util.Set<String> getMustPrecede()      { return mustPrecede; }
    }

    /** 步骤 id → StepDescriptor 有序映射，顺序即推荐执行顺序 */
    private static final Map<String, StepDescriptor> STEP_REGISTRY;
    static {
        Map<String, StepDescriptor> m = new LinkedHashMap<String, StepDescriptor>();

        // ── 第一层：Payload 编码（先于分块，确保编码后的字符串再被分块） ───────────
        // mustPrecede CHUNK_PAYLOAD；incompatibleWith PACK_PAYLOAD（两者均转换 payload 字面量）
        m.put("XOR_PAYLOAD_ENCODE", new StepDescriptor(
                "XOR_PAYLOAD_ENCODE", "XOR Payload 编码",
                "对 base64 payload 随机单字节 XOR 扰动：解码→XOR→重编码，生成与原始 payload 完全不同的 base64 字符串，" +
                "破坏 hash 指纹检测；注入随机命名的 helper 方法在运行时 XOR 还原，对调用方透明。" +
                "建议在 Payload 分块之前执行，两者叠加效果更强",
                true, true, true,
                new String[]{"PACK_PAYLOAD"},
                new String[]{"CHUNK_PAYLOAD", "UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX"}));
        // mustPrecede CHUNK_PAYLOAD；incompatibleWith XOR_PAYLOAD_ENCODE
        m.put("PACK_PAYLOAD", new StepDescriptor(
                "PACK_PAYLOAD", "Payload 双字符打包",
                "将 base64 payload 字符串相邻两个字符打包为一个 char（高低各 8 位），字符串长度减半，" +
                "base64 字符集特征完全消失；注入随机命名 helper 在运行时还原，对 Base64.decode() 调用方透明。" +
                "建议在 Payload 分块之前执行，两者叠加效果更强",
                true, true, true,
                new String[]{"XOR_PAYLOAD_ENCODE"},
                new String[]{"CHUNK_PAYLOAD", "UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX"}));

        // ── 第二层：字符串字面量混淆（四选一，互斥） ────────────────────────────────
        // incompatibleWith GHOST_BITS_ENCODE, BYTE_ARRAY_ENCODE, PACK_TWO_TO_ONE
        m.put("SPLIT_STRING_LITERALS", new StepDescriptor(
                "SPLIT_STRING_LITERALS", "字符串字面量拆分",
                "将敏感字符串在随机位置一分为二，如 \"defineClass\" → \"defin\"+\"eClass\"",
                true, true, true,
                new String[]{"GHOST_BITS_ENCODE", "BYTE_ARRAY_ENCODE", "PACK_TWO_TO_ONE"},
                new String[0]));
        // incompatibleWith SPLIT_STRING_LITERALS, BYTE_ARRAY_ENCODE, PACK_TWO_TO_ONE
        m.put("GHOST_BITS_ENCODE", new StepDescriptor(
                "GHOST_BITS_ENCODE", "Ghost Bits 编码",
                "Cast Attack（Black Hat Asia 2026）：将敏感字符串替换为 helperName(\"汉字...\") 调用；" +
                "汉字低字节 = 原始 ASCII，helper 方法名随机生成并注入 <%! %> 声明块；" +
                "读代码时只见连续汉字，WAF 无特征可匹配，(byte)ch 截断在运行时完整还原",
                true, true, true,
                new String[]{"SPLIT_STRING_LITERALS", "BYTE_ARRAY_ENCODE", "PACK_TWO_TO_ONE"},
                new String[0]));
        // incompatibleWith SPLIT_STRING_LITERALS, GHOST_BITS_ENCODE, PACK_TWO_TO_ONE
        m.put("BYTE_ARRAY_ENCODE", new StepDescriptor(
                "BYTE_ARRAY_ENCODE", "字节数组编码",
                "将敏感字符串字面量替换为 new String(new byte[]{100,101,...}) 形式，" +
                "无需注入 helper 方法；new String(new byte[]{}) 是 JDK 标准写法，在正常代码中大量出现，误报率高",
                true, true, true,
                new String[]{"SPLIT_STRING_LITERALS", "GHOST_BITS_ENCODE", "PACK_TWO_TO_ONE"},
                new String[0]));
        // incompatibleWith SPLIT_STRING_LITERALS, GHOST_BITS_ENCODE, BYTE_ARRAY_ENCODE
        m.put("PACK_TWO_TO_ONE", new StepDescriptor(
                "PACK_TWO_TO_ONE", "双字符打包编码",
                "将相邻两个 ASCII 字符打包进一个 char 的高低各 8 位，字符串长度减半；" +
                "高低字节均来自真实源字符（无随机噪声），注入随机命名 helper 在运行时还原。" +
                "仅处理全 ASCII 字面量，含非 ASCII 字符时自动跳过",
                true, true, true,
                new String[]{"SPLIT_STRING_LITERALS", "GHOST_BITS_ENCODE", "BYTE_ARRAY_ENCODE"},
                new String[0]));

        // ── 第三层：Payload 分块 ────────────────────────────────────────────────────
        m.put("CHUNK_PAYLOAD", new StepDescriptor(
                "CHUNK_PAYLOAD", "Payload 分块",
                "将 base64 payload 字符串随机切分为 16-40 字符的小块拼接，消除大段连续 base64 特征",
                true, true, true));

        // ── 第四层：标识符重命名 ─────────────────────────────────────────────────────
        m.put("IDENTIFIER_RENAME", new StepDescriptor(
                "IDENTIFIER_RENAME", "特征变量名重命名",
                "将 scriptlet/declaration 块内已知的 WebShell 特征变量名（classBytes、unsafe、clazz 等）替换为随机字段名，" +
                "消除静态签名库和 YARA 规则对固定变量名的匹配；与 TemplateRenderer 的占位符随机化正交，作为补充层",
                true, true, true,
                new String[0],
                new String[]{"UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX"}));

        // ── 第五层：噪声注入（必须在 Unicode 编码之前） ─────────────────────────────
        m.put("INJECT_SCRIPTLET_NOISE", new StepDescriptor(
                "INJECT_SCRIPTLET_NOISE", "Scriptlet 内噪声注入",
                "在每个 scriptlet 块开头注入 1-2 条随机无副作用 Java 声明语句（如 int v=Runtime.getRuntime().availableProcessors()），" +
                "使每次生成的 scriptlet 内容不同，打断基于 scriptlet 块内容哈希/签名的检测",
                true, true, true,
                new String[0],
                new String[]{"UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX"}));
        m.put("DEAD_BLOCK_INJECT", new StepDescriptor(
                "DEAD_BLOCK_INJECT", "死代码块注入",
                "在每个 scriptlet 块开头注入 1 条引用 Spring/Struts/JNDI/Servlet API 的死代码块（try-catch 或 if(false)），" +
                "使 ML 分类器将其特征识别为普通 Servlet 代码，增加人工 audit 追踪成本；与 Scriptlet 内噪声注入正交",
                true, true, true,
                new String[0],
                new String[]{"UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX"}));
        m.put("INSERT_SCRIPT_NOISE", new StepDescriptor(
                "INSERT_SCRIPT_NOISE", "噪声标签注入",
                "在 scriptlet 边界（%> 与 <%）之间随机插入 script/style/comment 噪声标签，打断 WAF 正则匹配",
                true, false, true));

        // ── 第六层：Unicode 编码（最后的字符级混淆，仅选其一） ────────────────────
        // incompatibleWith UNICODE_ENCODE_JSPX
        m.put("UNICODE_ENCODE_JSP", new StepDescriptor(
                "UNICODE_ENCODE_JSP", "Unicode 编码（JSP）",
                "将代码中字母数字字符随机转为 \\uXXXX 形式，随机混用 1-4 个 u 前缀变体，仅编码 scriptlet 内容",
                true, false, true,
                new String[]{"UNICODE_ENCODE_JSPX"},
                new String[0]));
        // incompatibleWith UNICODE_ENCODE_JSP
        m.put("UNICODE_ENCODE_JSPX", new StepDescriptor(
                "UNICODE_ENCODE_JSPX", "Unicode 编码（JSPX）",
                "同上，但跳过 JSPX XML 结构标签（jsp:root / jsp:declaration 等）",
                false, true, true,
                new String[]{"UNICODE_ENCODE_JSP"},
                new String[0]));

        // ── 第七层：HTML 壳（最外层，仅部署页使用） ────────────────────────────────
        m.put("WRAP_HTML_JS", new StepDescriptor(
                "WRAP_HTML_JS", "HTML 壳包裹",
                "在 JSP 外层套 HTML + jQuery ready 注释壳，使文件头看起来是普通 HTML，规避文件内容扫描；仅适用于内存马部署页",
                true, false, false));  // webshellCompatible=false：会改变响应体，导致 WebShell 断连

        // ── 第八层：格式随机化（最后执行，打破 LLM 指纹） ───────────────────────────
        m.put("NORMALIZE_WHITESPACE", new StepDescriptor(
                "NORMALIZE_WHITESPACE", "格式随机化",
                "随机化 scriptlet 块内的缩进风格（2/3/4空格或tab）与空行节奏，" +
                "打破 LLM 生成代码的统计指纹（token 分布、缩进习惯等），使输出格式特征无规律可循；" +
                "不改变代码语义，对 JSP 和 JSPX 均适用，建议放在 pipeline 最后执行",
                true, true, true));

        STEP_REGISTRY = Collections.unmodifiableMap(m);
    }

    /**
     * 返回所有可用步骤的描述列表，顺序为推荐执行顺序。
     * 直接序列化给前端使用。
     */
    public static List<StepDescriptor> getStepDescriptors() {
        return new ArrayList<StepDescriptor>(STEP_REGISTRY.values());
    }

    // -------------------------------------------------------------------------
    // 预置方案描述（供前端快捷选择使用）
    // -------------------------------------------------------------------------

    /**
     * 根据前端传入的有序步骤 ID 列表构建自定义 pipeline，并做约束校验：
     * <ul>
     *   <li>未知 ID 忽略（不抛异常）</li>
     *   <li>互斥步骤（{@link StepDescriptor#incompatibleWith}）同时出现时，后出现的被跳过并打印 warning</li>
     *   <li>顺序约束（{@link StepDescriptor#mustPrecede}）颠倒时打印 warning，但仍按用户指定顺序执行</li>
     * </ul>
     *
     * @param stepIds 有序步骤 ID 列表，如 ["SPLIT_STRING_LITERALS","UNICODE_ENCODE_JSP"]
     * @return 对应的 JspObfuscationPipeline
     */
    public static JspObfuscationPipeline fromStepIds(List<String> stepIds) {
        if (stepIds == null || stepIds.isEmpty()) {
            return builder().build();
        }

        // 第一遍：过滤未知 ID，收集最终有效 ID 列表
        List<String> validIds = new ArrayList<String>();
        for (String id : stepIds) {
            if (id != null && STEP_REGISTRY.containsKey(id)) {
                validIds.add(id);
            }
        }

        // 互斥检查：已接受的 ID 集合，遇到互斥对时跳过后者
        java.util.Set<String> accepted = new java.util.LinkedHashSet<String>();
        java.util.Set<String> skipped  = new java.util.HashSet<String>();
        for (String id : validIds) {
            if (skipped.contains(id)) {
                System.err.println("[JspObfuscationPipeline] 步骤 " + id
                        + " 与已接受步骤互斥，已跳过");
                continue;
            }
            StepDescriptor desc = STEP_REGISTRY.get(id);
            accepted.add(id);
            // 把与本步骤互斥的步骤加入 skipped 集合
            for (String incompatible : desc.getIncompatibleWith()) {
                skipped.add(incompatible);
            }
        }

        // 顺序检查：A mustPrecede B，若 B 在 accepted 中出现在 A 之前则 warning
        List<String> acceptedList = new ArrayList<String>(accepted);
        for (int i = 0; i < acceptedList.size(); i++) {
            String id = acceptedList.get(i);
            StepDescriptor desc = STEP_REGISTRY.get(id);
            for (String mustBeAfter : desc.getMustPrecede()) {
                int laterIdx = acceptedList.indexOf(mustBeAfter);
                if (laterIdx != -1 && laterIdx < i) {
                    System.err.println("[JspObfuscationPipeline] 顺序警告：" + id
                            + " 应在 " + mustBeAfter + " 之前执行，当前顺序可能导致混淆效果不正确");
                }
            }
        }

        // 按最终有序列表构建 pipeline
        Builder builder = builder();
        for (String id : acceptedList) {
            JspObfuscationStep step = resolveStep(id);
            if (step != null) {
                builder.add(step);
            }
        }
        return builder.build();
    }

    /** 将步骤 ID 字符串解析为对应的 JspObfuscationStep 实例 */
    private static JspObfuscationStep resolveStep(String id) {
        if (id == null) return null;
        switch (id) {
            case "SPLIT_STRING_LITERALS":    return SPLIT_STRING_LITERALS;
            case "CHUNK_PAYLOAD":            return CHUNK_PAYLOAD;
            case "GHOST_BITS_ENCODE":        return GHOST_BITS_ENCODE;
            case "INSERT_SCRIPT_NOISE":      return INSERT_SCRIPT_NOISE;
            case "INJECT_SCRIPTLET_NOISE":   return INJECT_SCRIPTLET_NOISE;
            case "UNICODE_ENCODE_JSP":       return UNICODE_ENCODE_JSP;
            case "UNICODE_ENCODE_JSPX":      return UNICODE_ENCODE_JSPX;
            case "WRAP_HTML_JS":             return WRAP_HTML_JS;
            case "IDENTIFIER_RENAME":        return IDENTIFIER_RENAME;
            case "XOR_PAYLOAD_ENCODE":       return XOR_PAYLOAD_ENCODE;
            case "PACK_PAYLOAD":             return PACK_PAYLOAD;
            case "DEAD_BLOCK_INJECT":        return DEAD_BLOCK_INJECT;
            case "BYTE_ARRAY_ENCODE":        return BYTE_ARRAY_ENCODE;
            case "PACK_TWO_TO_ONE":          return PACK_TWO_TO_ONE;
            case "NORMALIZE_WHITESPACE":     return NORMALIZE_WHITESPACE;
            default:                         return null;
        }
    }

    // -------------------------------------------------------------------------
    // 内置步骤常量
    // -------------------------------------------------------------------------

    /** 将敏感字符串字面量在随机位置一分为二，如 "defineClass" → "defin"+"eClass" */
    public static final JspObfuscationStep SPLIT_STRING_LITERALS = Util::splitStringLiterals;

    /**
     * 将 base64 payload 字符串随机切成 16-40 字符的小块拼接，
     * 消除大段连续 base64 特征。对 JSP 和 JSPX 均适用。
     */
    public static final JspObfuscationStep CHUNK_PAYLOAD = Util::chunkPayload;

    /**
     * Ghost Bits（Cast Attack）编码：将敏感字符串字面量替换为运行时通过字节截断还原的表达式。
     * 例如 {@code "defineClass"} 变为 {@code new String(new byte[]{(byte)0x2164,(byte)0x3165,...})}。
     * 适用于 JSP 和 JSPX，不影响响应体。
     */
    public static final JspObfuscationStep GHOST_BITS_ENCODE = Util::ghostBitsEncode;

    /**
     * 在 {@code %>} 与 {@code <%} 之间插入随机噪声标签。
     * 仅适用于 JSP，不适用于 JSPX（会破坏 XML 结构）。
     */
    public static final JspObfuscationStep INSERT_SCRIPT_NOISE = Util::insertScriptNoiseTags;

    /**
     * 在每个 scriptlet 块开头注入 1-2 条随机无副作用 Java 声明语句，
     * 使每次生成的 scriptlet 内容不同，打断基于内容哈希/签名的检测。
     * 对 JSP 和 JSPX 均适用，必须在 Unicode 编码之前执行。
     */
    public static final JspObfuscationStep INJECT_SCRIPTLET_NOISE = Util::injectScriptletNoise;

    /** Unicode 多 u 转义编码，JSP 模式（保留 JSP 指令行不编码） */
    public static final JspObfuscationStep UNICODE_ENCODE_JSP = code -> JspUnicoder.encode(code, true);

    /** Unicode 多 u 转义编码，JSPX 模式（保留 XML 结构标签不编码） */
    public static final JspObfuscationStep UNICODE_ENCODE_JSPX = code -> JspUnicoder.encode(code, false);

    /**
     * 外层 HTML + jQuery ready 注释壳包裹。
     * 仅适用于 JSP，不适用于 JSPX（会破坏 XML 结构）。
     */
    public static final JspObfuscationStep WRAP_HTML_JS = Util::wrapWithHtmlJs;

    /**
     * 将 scriptlet/declaration 块内已知的 WebShell 特征变量名（classBytes、unsafe、clazz 等）
     * 替换为随机字段名，消除静态签名库和 YARA 规则对固定变量名的匹配。
     * 与 TemplateRenderer 的占位符随机化正交，作为补充层。
     * 必须在 Unicode 编码之前执行。
     */
    public static final JspObfuscationStep IDENTIFIER_RENAME = Util::renameIdentifiers;

    /**
     * 对 base64 payload 随机单字节 XOR 扰动，破坏 hash 指纹检测。
     * 注入随机命名 helper 方法在运行时还原，对调用方透明。
     * 建议在 CHUNK_PAYLOAD 之前执行，两者叠加效果更强。
     * 与 PACK_PAYLOAD 互斥。
     */
    public static final JspObfuscationStep XOR_PAYLOAD_ENCODE = Util::xorPayloadEncode;

    /**
     * 将 base64 payload 字符串相邻两个字符打包为一个 char（高低各 8 位），
     * 字符串长度减半，base64 字符集特征完全消失。
     * 注入随机命名 helper 在运行时还原，对 Base64.decode() 调用方透明。
     * 建议在 CHUNK_PAYLOAD 之前执行。与 XOR_PAYLOAD_ENCODE 互斥。
     */
    public static final JspObfuscationStep PACK_PAYLOAD = Util::packPayload;

    /**
     * 在每个 scriptlet 块开头注入 1 条引用 Spring/Struts/JNDI/Servlet API 的死代码块，
     * 使 ML 分类器将其特征识别为普通 Servlet 代码，增加人工 audit 追踪成本。
     * 与 INJECT_SCRIPTLET_NOISE 正交，可叠加使用。
     * 必须在 Unicode 编码之前执行。
     */
    public static final JspObfuscationStep DEAD_BLOCK_INJECT = Util::injectDeadBlocks;

    /**
     * 将敏感字符串字面量替换为 {@code new String(new byte[]{100,101,...})} 形式，
     * 无需注入 helper 方法。{@code new String(new byte[]{})} 是 JDK 标准写法，
     * 在正常代码中大量出现，误报率高。
     * 与 SPLIT_STRING_LITERALS、GHOST_BITS_ENCODE、PACK_TWO_TO_ONE 互斥。
     */
    public static final JspObfuscationStep BYTE_ARRAY_ENCODE = Util::byteArrayEncode;

    /**
     * 将敏感字符串字面量中相邻两个 ASCII 字符打包进一个 char 的高低各 8 位，
     * 字符串长度减半；注入随机命名 helper 在运行时取高低字节还原。
     * 与 SPLIT_STRING_LITERALS、GHOST_BITS_ENCODE、BYTE_ARRAY_ENCODE 互斥。
     */
    public static final JspObfuscationStep PACK_TWO_TO_ONE = Util::packTwoToOne;

    /**
     * 随机化 scriptlet 块内的缩进风格（2/3/4 空格或 tab）与空行节奏，
     * 打破 LLM 生成代码的统计指纹（token 分布、缩进习惯）。
     * 不改变代码语义，对 JSP 和 JSPX 均适用，建议放在 pipeline 最后执行。
     */
    public static final JspObfuscationStep NORMALIZE_WHITESPACE = Util::normalizeWhitespace;

    // -------------------------------------------------------------------------
    // 流水线核心
    // -------------------------------------------------------------------------

    private final List<JspObfuscationStep> steps;

    private JspObfuscationPipeline(List<JspObfuscationStep> steps) {
        this.steps = steps;
    }

    /**
     * 按步骤顺序依次应用所有混淆步骤。
     *
     * @param code 原始 JSP/JSPX 代码
     * @return 混淆后代码
     */
    public String apply(String code) {
        for (JspObfuscationStep step : steps) {
            code = step.apply(code);
        }
        return code;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<JspObfuscationStep> steps = new ArrayList<JspObfuscationStep>();

        public Builder add(JspObfuscationStep step) {
            if (step != null) {
                steps.add(step);
            }
            return this;
        }

        public JspObfuscationPipeline build() {
            return new JspObfuscationPipeline(new ArrayList<JspObfuscationStep>(steps));
        }
    }

    // -------------------------------------------------------------------------
    // 预置组合
    // -------------------------------------------------------------------------

    /**
     * 标准 JSP 混淆：字符串拆分 → payload 分块 → scriptlet 内噪声 → 噪声标签注入。
     * 变量名/类名随机化由 TemplateRenderer 在渲染阶段完成。
     */
    public static JspObfuscationPipeline jspDefault() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .add(INSERT_SCRIPT_NOISE)
                .build();
    }

    /**
     * JSP Unicode 混淆：在标准基础上追加多 u Unicode 转义编码。
     */
    public static JspObfuscationPipeline jspUnicode() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .add(INSERT_SCRIPT_NOISE)
                .add(UNICODE_ENCODE_JSP)
                .build();
    }

    /**
     * JSP HTML 壳混淆：在标准基础上用 HTML + jQuery 注释包裹，伪装成普通 HTML 文件。
     */
    public static JspObfuscationPipeline jspHtmlWrapped() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .add(INSERT_SCRIPT_NOISE)
                .add(WRAP_HTML_JS)
                .build();
    }

    /**
     * JSP 全量隐蔽：字符串拆分 → payload 分块 → scriptlet 内噪声 → 边界噪声 → Unicode 编码 → HTML 壳。
     * 叠加所有展示性混淆策略，特征最少。变量名/类名随机化由 TemplateRenderer 完成。
     */
    public static JspObfuscationPipeline jspFullStealth() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .add(INSERT_SCRIPT_NOISE)
                .add(UNICODE_ENCODE_JSP)
                .add(WRAP_HTML_JS)
                .build();
    }

    /**
     * 标准 JSPX 混淆：字符串拆分 → payload 分块 → scriptlet 内噪声。
     * 不含噪声标签注入和 HTML 壳（会破坏 XML 结构）。
     */
    public static JspObfuscationPipeline jspxDefault() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .build();
    }

    /**
     * JSPX Unicode 混淆：在标准基础上追加 JSPX 模式 Unicode 编码。
     */
    public static JspObfuscationPipeline jspxUnicode() {
        return builder()
                .add(SPLIT_STRING_LITERALS)
                .add(CHUNK_PAYLOAD)
                .add(INJECT_SCRIPTLET_NOISE)
                .add(UNICODE_ENCODE_JSPX)
                .build();
    }
}
