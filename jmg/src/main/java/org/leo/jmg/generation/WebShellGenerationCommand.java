package org.leo.jmg.generation;

import org.leo.core.entity.Disguise;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * WebShell 生成入口的类型化命令。
 *
 * <p>负责统一 JSP/JSPX、协议、版本和可选参数的默认值，避免 Web 与 AI
 * 入口分别维护配置分支。</p>
 */
public final class WebShellGenerationCommand {

    private final Disguise requestDisguise;
    private final Disguise responseDisguise;
    private final GenerationPlan.ArtifactKind artifactKind;
    private final String coreClassName;
    private final TransportProtocol protocol;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final int responseCode;
    private final String payloadKey;
    private final List<String> obfuscationSteps;
    private final Long obfuscationSeed;

    private WebShellGenerationCommand(Builder builder) {
        this.requestDisguise = snapshot(builder.requestDisguise, true);
        this.responseDisguise = snapshot(builder.responseDisguise, false);
        this.artifactKind = parseArtifactKind(builder.artifactType);
        this.coreClassName = trimToNull(builder.coreClassName);
        this.protocol = parseProtocol(builder.protocol);
        this.targetJavaVersion = parseTargetJavaVersion(builder.targetJavaVersion);
        this.servletNamespace = parseServletNamespace(builder.servletNamespace);
        this.responseCode = builder.responseCode == null ? 200 : builder.responseCode;
        this.payloadKey = trimToNull(builder.payloadKey);
        this.obfuscationSteps = snapshot(builder.obfuscationSteps);
        this.obfuscationSeed = builder.obfuscationSeed;
    }

    public static Builder builder(Disguise requestDisguise,
                                  Disguise responseDisguise,
                                  String artifactType) {
        return new Builder(requestDisguise, responseDisguise, artifactType);
    }

    ShellGeneratorConfig toConfig() {
        ShellGeneratorConfig.Builder builder =
                ShellGeneratorConfig.builder(requestDisguise, responseDisguise)
                        .protocol(protocol.getValue())
                        .targetJavaVersion(targetJavaVersion)
                        .servletNamespace(servletNamespace)
                        .respCode(responseCode);
        if (payloadKey != null) builder.payloadKey(payloadKey);
        if (coreClassName != null) {
            builder.coreClassName(coreClassName);
        }
        if (obfuscationSteps != null) {
            builder.jspObfuscationSteps(obfuscationSteps);
        }
        if (obfuscationSeed != null) {
            builder.obfuscationSeed(obfuscationSeed);
        }
        return builder.build();
    }

    public GenerationPlan.ArtifactKind getArtifactKind() {
        return artifactKind;
    }

    public String getArtifactType() {
        return artifactKind.name();
    }

    public static final class Builder {
        private final Disguise requestDisguise;
        private final Disguise responseDisguise;
        private final String artifactType;
        private String coreClassName;
        private String protocol;
        private String targetJavaVersion;
        private String servletNamespace;
        private Integer responseCode;
        private String payloadKey;
        private List<String> obfuscationSteps;
        private Long obfuscationSeed;

        private Builder(Disguise requestDisguise,
                        Disguise responseDisguise,
                        String artifactType) {
            this.requestDisguise = requestDisguise;
            this.responseDisguise = responseDisguise;
            this.artifactType = artifactType;
        }

        public Builder coreClassName(String coreClassName) {
            this.coreClassName = coreClassName;
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

        public Builder obfuscationSeed(Long obfuscationSeed) {
            this.obfuscationSeed = obfuscationSeed;
            return this;
        }

        public WebShellGenerationCommand build() {
            return new WebShellGenerationCommand(this);
        }
    }

    private static GenerationPlan.ArtifactKind parseArtifactKind(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
        if ("JSP".equals(normalized)) {
            return GenerationPlan.ArtifactKind.JSP;
        }
        if ("JSPX".equals(normalized)) {
            return GenerationPlan.ArtifactKind.JSPX;
        }
        throw new IllegalArgumentException(
                "shellType 必须是 JSP 或 JSPX，当前值: " + value);
    }

    private static TransportProtocol parseProtocol(String value) {
        return isBlank(value)
                ? TransportProtocol.HTTP
                : TransportProtocol.parse(value);
    }

    private static TargetJavaVersion parseTargetJavaVersion(String value) {
        return isBlank(value)
                ? TargetJavaVersion.AUTO
                : TargetJavaVersion.parse(value);
    }

    private static ServletNamespace parseServletNamespace(String value) {
        return isBlank(value)
                ? ServletNamespace.AUTO
                : ServletNamespace.parse(value);
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
