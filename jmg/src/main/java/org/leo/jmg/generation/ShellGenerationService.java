package org.leo.jmg.generation;

import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.mem.packer.PackerCompatibilityResult;
import org.leo.jmg.mem.packer.PackerRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web、AI 等应用入口共享的生成服务门面。
 */
public final class ShellGenerationService {

    public ShellGenerationOutcome generateWebShell(
            WebShellGenerationCommand command) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("WebShellGenerationCommand 不能为空");
        }
        ShellGeneratorConfig config = command.toConfig();
        ShellGenerator generator =
                new ShellGenerator(GenerationRequest.from(config));
        GenerationResult result =
                command.getArtifactKind() == GenerationPlan.ArtifactKind.JSP
                        ? generator.generateJspShell()
                        : generator.generateJspxShell();

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("type", command.getArtifactType());
        metadata.put("coreClassName", result.getCoreClassName());
        metadata.put("protocol", result.getProtocol().getValue());
        metadata.put("targetJavaVersion", result.getTargetJavaVersion().getValue());
        metadata.put("obfuscationSeed", Long.toString(result.getObfuscationSeed()));
        metadata.put("servletNamespace", result.getServletNamespace().getValue());
        metadata.put("headerConfig", command.getHeaderConfig());
        addSizeMetadata(metadata, result.getContent());
        return new ShellGenerationOutcome(result, metadata);
    }

    public ShellGenerationOutcome generateMemoryShell(
            MemoryShellGenerationCommand command) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("MemoryShellGenerationCommand 不能为空");
        }
        ShellGeneratorConfig config = command.toConfig();
        GenerationResult result =
                new ShellGenerator(GenerationRequest.from(config))
                        .generateFormattedInjector();

        PackerCompatibilityResult compatibility =
                PackerRegistry.evaluateCompatibility(
                        command.getPackerType(),
                        result.getTargetJavaVersion(),
                        command.isBypassJavaModuleEffective());
        List<String> warnings =
                new ArrayList<String>(compatibility.getWarnings());
        if (result.getServletNamespace() == ServletNamespace.JAKARTA
                && result.getTargetJavaVersion().isAuto()) {
            warnings.add(
                    "Jakarta Servlet 需要 JDK 8+，auto 模式无法确认目标 JDK");
        }

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("packerType", command.getPackerType());
        metadata.put("shellType", command.getInjectorName());
        metadata.put("protocol", result.getProtocol().getValue());
        metadata.put("serverType", command.getServerType());
        metadata.put("serverVersion", command.getServerVersion());
        metadata.put("coreClassName", result.getCoreClassName());
        metadata.put("injectorClassName", result.getInjectorClassName());
        metadata.put("shellClassName", result.getShellClassName());
        metadata.put("urlPattern", result.getUrlPattern());
        metadata.put("isAbstractTranslet", result.isAbstractTranslet());
        metadata.put("byPassJavaModuleRequested", command.isBypassJavaModule());
        metadata.put("byPassJavaModule", command.isBypassJavaModuleEffective());
        metadata.put("lambdaSuffix", command.isLambdaSuffix());
        metadata.put("staticInitialize", command.isStaticInitialize());
        metadata.put("shrink", command.isShrink());
        metadata.put("targetJavaVersion", result.getTargetJavaVersion().getValue());
        metadata.put("obfuscationSeed", Long.toString(result.getObfuscationSeed()));
        metadata.put("compatibilityWarnings",
                java.util.Collections.unmodifiableList(warnings));
        metadata.put("servletNamespace", result.getServletNamespace().getValue());
        metadata.put("headerConfig", command.getHeaderConfig());
        if ("UpgradeInjector".equals(command.getInjectorName())) {
            metadata.put("activationConfig",
                    "Connection: Upgrade; Upgrade: " + result.getShellClassName());
        }
        metadata.put("templateMutated", command.hasCustomJspTemplate());
        Map<String, Object> packingConfig = new LinkedHashMap<String, Object>();
        packingConfig.put("packerType", command.getPackerType());
        packingConfig.put("serverVersion", command.getServerVersion());
        packingConfig.put("targetJavaVersion", result.getTargetJavaVersion().getValue());
        packingConfig.put("servletNamespace", result.getServletNamespace().getValue());
        packingConfig.put("abstractTranslet", result.isAbstractTranslet());
        packingConfig.put("byPassJavaModule", command.isBypassJavaModuleEffective());
        packingConfig.put("lambdaSuffix", command.isLambdaSuffix());
        packingConfig.put("staticInitialize", command.isStaticInitialize());
        packingConfig.put("shrink", command.isShrink());
        metadata.put("packingConfig",
                java.util.Collections.unmodifiableMap(packingConfig));
        addSizeMetadata(metadata, result.getContent());
        return new ShellGenerationOutcome(result, metadata);
    }

    private static void addSizeMetadata(Map<String, Object> metadata,
                                        String content) {
        metadata.put("lines", content.split("\n").length);
        metadata.put("chars", content.length());
    }
}
