package org.leo.jmg.mem.packer.jsp;

/**
 * JSP/JSPX 单步混淆策略接口。
 * <p>
 * 每个实现只做一件事（变量名随机化、字符串拆分、噪声注入等），
 * 通过 {@link JspObfuscationPipeline} 按顺序组合成完整混淆链。
 */
@FunctionalInterface
public interface JspObfuscationStep {

    /**
     * 对 JSP/JSPX 代码应用本步骤混淆，返回处理后的代码。
     *
     * @param code 输入代码
     * @return 混淆后代码
     */
    String apply(String code);
}
