package org.leo.jmg.mem.packer.groovy;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "GroovyScriptEngine", group = "Groovy", order = 2)
public class GroovyScriptEnginePacker implements Packer {
    String template = "new javax.script.ScriptEngineManager().getEngineByName('js').eval('{{script}}')";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}
