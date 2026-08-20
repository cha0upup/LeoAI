package org.leo.jmg.generation;

import org.leo.core.entity.Disguise;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;

/** 独立生成 LeoCore 所需的不可变命令。 */
public final class CoreArtifactGenerationCommand {

    private final Disguise requestDisguise;
    private final Disguise responseDisguise;
    private final String coreClassName;
    private final TransportProtocol protocol;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final String payloadKey;
    private final Long obfuscationSeed;

    private CoreArtifactGenerationCommand(Builder builder) {
        this.requestDisguise = snapshot(builder.requestDisguise, true);
        this.responseDisguise = snapshot(builder.responseDisguise, false);
        this.coreClassName = trimToNull(builder.coreClassName);
        this.protocol = isBlank(builder.protocol)
                ? TransportProtocol.HTTP : TransportProtocol.parse(builder.protocol);
        this.targetJavaVersion = isBlank(builder.targetJavaVersion)
                ? TargetJavaVersion.AUTO : TargetJavaVersion.parse(builder.targetJavaVersion);
        this.servletNamespace = isBlank(builder.servletNamespace)
                ? ServletNamespace.AUTO : ServletNamespace.parse(builder.servletNamespace);
        this.payloadKey = trimToNull(builder.payloadKey);
        this.obfuscationSeed = builder.obfuscationSeed;
    }

    public static Builder builder(Disguise requestDisguise, Disguise responseDisguise) {
        return new Builder(requestDisguise, responseDisguise);
    }

    ShellGeneratorConfig toConfig() {
        ShellGeneratorConfig.Builder builder = ShellGeneratorConfig
                .builder(requestDisguise, responseDisguise)
                .protocol(protocol.getValue())
                .targetJavaVersion(targetJavaVersion)
                .servletNamespace(servletNamespace);
        if (payloadKey != null) builder.payloadKey(payloadKey);
        if (coreClassName != null) builder.coreClassName(coreClassName);
        if (obfuscationSeed != null) builder.obfuscationSeed(obfuscationSeed.longValue());
        return builder.build();
    }

    public static final class Builder {
        private final Disguise requestDisguise;
        private final Disguise responseDisguise;
        private String coreClassName;
        private String protocol;
        private String targetJavaVersion;
        private String servletNamespace;
        private String payloadKey;
        private Long obfuscationSeed;

        private Builder(Disguise requestDisguise, Disguise responseDisguise) {
            this.requestDisguise = requestDisguise;
            this.responseDisguise = responseDisguise;
        }

        public Builder coreClassName(String value) {
            this.coreClassName = value;
            return this;
        }

        public Builder protocol(String value) {
            this.protocol = value;
            return this;
        }

        public Builder targetJavaVersion(String value) {
            this.targetJavaVersion = value;
            return this;
        }

        public Builder servletNamespace(String value) {
            this.servletNamespace = value;
            return this;
        }

        public Builder payloadKey(String value) {
            this.payloadKey = value;
            return this;
        }

        public Builder obfuscationSeed(Long value) {
            this.obfuscationSeed = value;
            return this;
        }

        public CoreArtifactGenerationCommand build() {
            return new CoreArtifactGenerationCommand(this);
        }
    }

    private static Disguise snapshot(Disguise value, boolean request) {
        if (value == null) return null;
        Disguise result = new Disguise();
        if (request) {
            result.setTrafficDecodeBody(value.getTrafficDecodeBody());
        } else {
            result.setTrafficEncodeBody(value.getTrafficEncodeBody());
        }
        return result;
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
