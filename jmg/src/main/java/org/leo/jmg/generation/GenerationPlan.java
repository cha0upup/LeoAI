package org.leo.jmg.generation;

import org.leo.jmg.TransportProtocol;
import org.leo.jmg.catalog.GeneratorCatalog;
import org.leo.jmg.catalog.InjectorDescriptor;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerRegistry;

/**
 * 请求解析后的不可变执行计划。目录项、Packer 和派生开关只解析一次。
 */
public final class GenerationPlan {

    public enum ArtifactKind {
        JSP,
        JSPX,
        INJECTOR
    }

    private final GenerationRequest request;
    private final ArtifactKind artifactKind;
    private final InjectorDescriptor injectorDescriptor;
    private final Packer packer;
    private final boolean abstractTranslet;

    private GenerationPlan(GenerationRequest request,
                           ArtifactKind artifactKind,
                           InjectorDescriptor injectorDescriptor,
                           Packer packer,
                           boolean abstractTranslet) {
        this.request = request;
        this.artifactKind = artifactKind;
        this.injectorDescriptor = injectorDescriptor;
        this.packer = packer;
        this.abstractTranslet = abstractTranslet;
    }

    public static GenerationPlan forWebShell(GenerationRequest request,
                                             ArtifactKind artifactKind) {
        request.validateCommon();
        if (artifactKind != ArtifactKind.JSP && artifactKind != ArtifactKind.JSPX) {
            throw new IllegalArgumentException("WebShell 类型必须是 JSP 或 JSPX");
        }
        if (request.getProtocol() == TransportProtocol.WEBSOCKET) {
            throw new IllegalArgumentException(
                    "JSP/JSPX WebShell 仅支持 http 或 httpchunk；websocket 请使用内存构建");
        }
        if (isBlank(request.getHeaderName()) || isBlank(request.getHeaderValue())) {
            throw new IllegalArgumentException(
                    "JSP/JSPX WebShell 的 headerName 和 headerValue 不能为空");
        }
        return new GenerationPlan(request, artifactKind, null, null, false);
    }

    public static GenerationPlan forInjector(GenerationRequest request) {
        request.validateCommon();
        requireText(request.getServerType(),
                "生成注入器需要指定 serverType（目标应用服务器类型，如 Tomcat）");
        requireText(request.getInjectorName(),
                "生成注入器需要指定 shellType（注入器形态，如 FilterInjector）");
        requireText(request.getPackerType(), "配置类中 packerType 不能为空");

        String protocol = request.getProtocol().getValue();
        InjectorDescriptor descriptor = GeneratorCatalog.resolve(
                request.getServerType(), request.getInjectorName(), protocol);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "服务器类型 " + request.getServerType() + " 在 " + protocol
                            + " 协议下不支持 " + request.getInjectorName() + " 类型的注入器");
        }
        if (!descriptor.supportsServletNamespace(
                request.getEffectiveServletNamespace())) {
            throw new IllegalArgumentException(
                    request.getServerType() + " / " + request.getInjectorName()
                            + " 仅支持 javax.servlet，当前选择为 jakarta.servlet");
        }
        if (!descriptor.supportsServerVersion(request.getServerVersion())) {
            throw new IllegalArgumentException(
                    request.getServerType() + " / " + request.getInjectorName()
                            + " 需要 serverVersion，支持值: "
                            + descriptor.getSupportedServerVersions());
        }
        if ("Jetty".equalsIgnoreCase(request.getServerType())
                && "HandlerInjector".equals(request.getInjectorName())
                && "11".equals(request.getServerVersion())
                && request.getEffectiveServletNamespace()
                != org.leo.jmg.ServletNamespace.JAKARTA) {
            throw new IllegalArgumentException(
                    "Jetty 11 Handler 需要 jakarta servletNamespace");
        }
        if (!descriptor.supportsPacker(request.getPackerType())) {
            throw new IllegalArgumentException(
                    request.getServerType() + " / " + request.getInjectorName()
                            + " 支持的 Packer: " + descriptor.getSupportedPackers());
        }
        if (!descriptor.getSupportedPackers(request.getServerVersion()).isEmpty()
                && !containsIgnoreCase(
                descriptor.getSupportedPackers(request.getServerVersion()),
                request.getPackerType())) {
            throw new IllegalArgumentException(
                    request.getServerType() + " / " + request.getServerVersion()
                            + " 支持的 Packer: "
                            + descriptor.getSupportedPackers(request.getServerVersion()));
        }
        if (request.isStaticInitialize() && !descriptor.supportsStaticInitialize()) {
            throw new IllegalArgumentException(
                    request.getServerType() + " / " + request.getInjectorName()
                            + " 由 Agent JAR 的 premain/agentmain 驱动，不使用静态初始化挂载");
        }
        if ("AgentJarBase64".equalsIgnoreCase(request.getPackerType())
                && descriptor.getSupportedPackers().isEmpty()) {
            throw new IllegalArgumentException(
                    "AgentJarBase64 仅适用于声明 Agent JAR 装载能力的注入器");
        }

        PackerRegistry.validateProtocolCompatibility(request.getPackerType(), protocol);
        validateTransportFields(request);

        Packer packer = PackerRegistry.get(request.getPackerType());
        if (packer == null) {
            throw new IllegalArgumentException("不支持的 packerType: " + request.getPackerType());
        }
        PackerRegistry.validateCompatibility(
                request.getPackerType(),
                request.getTargetJavaVersion(),
                request.isBypassJavaModuleEffective());

        boolean abstractTranslet = request.isAbstractTransletRequested()
                || PackerRegistry.requiresAbstractTranslet(request.getPackerType());
        return new GenerationPlan(
                request, ArtifactKind.INJECTOR, descriptor, packer, abstractTranslet);
    }

    private static void validateTransportFields(GenerationRequest request) {
        TransportProtocol protocol = request.getProtocol();
        if (protocol.isHttpFamily()
                && (isBlank(request.getHeaderName()) || isBlank(request.getHeaderValue()))) {
            throw new IllegalArgumentException(
                    protocol.getValue()
                            + " 内存构建的 headerName 和 headerValue 不能为空");
        }
        if (protocol == TransportProtocol.WEBSOCKET
                && (request.getUrlPattern() == null
                || !request.getUrlPattern().startsWith("/")
                || request.getUrlPattern().contains("*"))) {
            throw new IllegalArgumentException(
                    "websocket 的 urlPattern 必须是以 / 开头且不含通配符 * 的端点路径");
        }
        if (protocol == TransportProtocol.HTTP_CHUNK) {
            int responseCode = request.getResponseCode();
            if (responseCode < 200
                    || responseCode == 204
                    || responseCode == 205
                    || responseCode == 304) {
                throw new IllegalArgumentException(
                        "httpchunk 响应状态必须允许持续响应体: " + responseCode);
            }
        }
    }

    private static void requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean containsIgnoreCase(java.util.List<String> values,
                                              String expected) {
        for (String value : values) {
            if (value.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    public GenerationRequest getRequest() {
        return request;
    }

    public ArtifactKind getArtifactKind() {
        return artifactKind;
    }

    public InjectorDescriptor getInjectorDescriptor() {
        return injectorDescriptor;
    }

    public Packer getPacker() {
        return packer;
    }

    public boolean isAbstractTranslet() {
        return abstractTranslet;
    }
}
