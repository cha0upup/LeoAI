package org.leo.jmg.mem.packer;

public class ClassPackerConfig {
    private String className;
    private byte[] classBytes;
    private String classBytesBase64Str;
    private boolean byPassJavaModule;
    /**
     * 用户自定义 JSP/JSPX 混淆步骤 ID 列表（有序）。
     * 为 null 或空时 JSP Packer 使用默认 preset；非空时按此顺序构建 pipeline。
     */
    private java.util.List<String> jspObfuscationSteps;

    /**
     * AI 生成的自定义 JSP 模板（含 {{VAR:}} / {{CLS:}} / {{base64Str}} 占位符）。
     * 非 null 时 JSP Packer 优先使用此模板，替代内置模板文件；
     * 为 null 时回退到各 Packer 的默认模板。
     */
    private String customTemplate;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public byte[] getClassBytes() {
        return classBytes;
    }

    public void setClassBytes(byte[] classBytes) {
        this.classBytes = classBytes;
    }

    public String getClassBytesBase64Str() {
        return classBytesBase64Str;
    }

    public void setClassBytesBase64Str(String classBytesBase64Str) {
        this.classBytesBase64Str = classBytesBase64Str;
    }

    public boolean isByPassJavaModule() {
        return byPassJavaModule;
    }

    public void setByPassJavaModule(boolean byPassJavaModule) {
        this.byPassJavaModule = byPassJavaModule;
    }

    public java.util.List<String> getJspObfuscationSteps() {
        return jspObfuscationSteps;
    }

    public void setJspObfuscationSteps(java.util.List<String> jspObfuscationSteps) {
        this.jspObfuscationSteps = jspObfuscationSteps;
    }

    public String getCustomTemplate() {
        return customTemplate;
    }

    public void setCustomTemplate(String customTemplate) {
        this.customTemplate = customTemplate;
    }
}
