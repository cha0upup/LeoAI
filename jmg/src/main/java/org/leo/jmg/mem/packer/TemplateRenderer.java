package org.leo.jmg.mem.packer;

import org.leo.core.util.request.ClassNameGenerator;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板渲染器：在模板渲染阶段完成结构性混淆，消除对正则后处理的依赖。
 *
 * <h3>支持的占位符</h3>
 * <ul>
 *   <li>{@code {{className}}} / {@code {{base64Str}}} — 全局占位符，直接替换为 config 值</li>
 *   <li>{@code {{VAR:name}}} — 变量名占位符；同一模板中相同 name 映射到相同的随机字段名，
 *       不同 name 映射到不同随机字段名</li>
 *   <li>{@code {{CLS:Name}}} — 内部类名占位符；同一模板中相同 Name 映射到相同的随机
 *       PascalCase 类名（如 DefaultLoader、CoreHelper）</li>
 * </ul>
 *
 * <h3>使用示例（JSP 模板片段）</h3>
 * <pre>
 *     public static class {{CLS:Definer}} extends ClassLoader {
 *         public {{CLS:Definer}}(ClassLoader {{VAR:cl}}) { super({{VAR:cl}}); }
 *         public Class defineClass(byte[] {{VAR:buf}}) {
 *             return defineClass(null, {{VAR:buf}}, 0, {{VAR:buf}}.length);
 *         }
 *     }
 *     ...
 *     byte[] {{VAR:bytecode}} = ...;
 *     new {{CLS:Definer}}(Thread.currentThread().getContextClassLoader())
 *         .defineClass({{VAR:bytecode}});
 * </pre>
 *
 * <h3>与 pipeline 的分工</h3>
 * <ul>
 *   <li>TemplateRenderer 负责结构性混淆（变量名/类名随机化），在生成代码时一步到位</li>
 *   <li>JspObfuscationPipeline 负责展示性混淆（字符串编码、噪声注入、Unicode 转义、HTML 壳），
 *       对已渲染的字符串代码做后处理</li>
 * </ul>
 */
public class TemplateRenderer {

    private static final Pattern VAR_PH = Pattern.compile("\\{\\{VAR:([\\w]+)\\}\\}");
    private static final Pattern CLS_PH = Pattern.compile("\\{\\{CLS:([\\w]+)\\}\\}");

    /**
     * 渲染模板：依次完成全局占位符替换、变量名随机化、内部类名随机化。
     *
     * @param template 原始模板内容（含占位符）
     * @param config   Packer 配置（提供 className / base64Str 等全局值）
     * @return 渲染后的代码（变量名/类名已随机化）
     */
    public static String render(String template, ClassPackerConfig config) {
        // 1. 全局占位符替换
        String code = template
                .replace("{{className}}", config.getClassName() != null ? config.getClassName() : "")
                .replace("{{base64Str}}", config.getClassBytesBase64Str() != null
                        ? config.getClassBytesBase64Str() : "");

        return renderVarsAndCls(code);
    }

    /**
     * 带额外占位符的渲染（适用于 bigIntegerStr 等非标准全局值）。
     * 额外替换在全局占位符替换后、变量名随机化前进行。
     *
     * @param template   原始模板内容
     * @param config     Packer 配置
     * @param extraPairs 额外 key-value 对，key 为占位符名（不含 {{}}），value 为替换内容
     * @return 渲染后的代码
     */
    public static String render(String template, ClassPackerConfig config,
                                Map<String, String> extraPairs) {
        String code = template
                .replace("{{className}}", config.getClassName() != null ? config.getClassName() : "")
                .replace("{{base64Str}}", config.getClassBytesBase64Str() != null
                        ? config.getClassBytesBase64Str() : "");

        if (extraPairs != null) {
            for (Map.Entry<String, String> entry : extraPairs.entrySet()) {
                code = code.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }

        return renderVarsAndCls(code);
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 对代码中的 VAR / CLS 占位符做随机化替换。
     * VAR 和 CLS 共享同一个 usedNames 集合，保证两类名称互不冲突。
     */
    private static String renderVarsAndCls(String code) {
        // 共享 used 集合，保证 VAR 和 CLS 生成的名称互不重复
        Set<String> usedNames = new HashSet<String>();

        // 2. 收集所有 VAR 占位符，为每个唯一 key 分配随机字段名
        Map<String, String> varMap = new LinkedHashMap<String, String>();
        Matcher vm = VAR_PH.matcher(code);
        while (vm.find()) {
            String key = vm.group(1);
            if (!varMap.containsKey(key)) {
                varMap.put(key, ClassNameGenerator.randomFieldName(usedNames));
            }
        }

        // 3. 收集所有 CLS 占位符，为每个唯一 key 分配随机类名
        Map<String, String> clsMap = new LinkedHashMap<String, String>();
        Matcher cm = CLS_PH.matcher(code);
        while (cm.find()) {
            String key = cm.group(1);
            if (!clsMap.containsKey(key)) {
                clsMap.put(key, ClassNameGenerator.randomSimpleClassName(usedNames));
            }
        }

        // 4. 批量替换（String.replace 比 replaceAll 快，且无需 Pattern.quote）
        for (Map.Entry<String, String> entry : varMap.entrySet()) {
            code = code.replace("{{VAR:" + entry.getKey() + "}}", entry.getValue());
        }
        for (Map.Entry<String, String> entry : clsMap.entrySet()) {
            code = code.replace("{{CLS:" + entry.getKey() + "}}", entry.getValue());
        }

        return code;
    }
}
