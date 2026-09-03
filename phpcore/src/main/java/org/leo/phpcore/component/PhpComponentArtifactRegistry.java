package org.leo.phpcore.component;

import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;
import org.leo.core.runtime.PuppetRuntime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classpath-backed PHP component artifacts deployed to targets on demand. */
@Component
public final class PhpComponentArtifactRegistry {

    private static final Pattern COMPONENT_ID = Pattern.compile("'id'\\s*=>\\s*'([^']+)'");
    private static final Pattern COMPONENT_VERSION = Pattern.compile("'version'\\s*=>\\s*'([^']+)'");
    private static final Set<String> COMPONENT_IDS = Set.of(
            "BasicInfoComponent", "ExecCommandComponent", "ExecCommandSimpleComponent", "FileComponent",
            "FileDownloadComponent", "FileUploadComponent", "ExecScriptComponent",
            "DatabaseComponent", "CompressComponent", "DecompressComponent", "PluginComponent",
            "HttpRequestComponent", "ProxyForwardComponent", "ReverseTunnelComponent",
            "ProcessComponent", "NetworkInfoComponent",
            "NetworkConnectionComponent", "NetworkProbeComponent", "ServiceComponent",
            "ScheduledTaskComponent", "RegistryComponent", "EventLogComponent",
            "FirewallComponent", "UserAccountComponent");

    private final Map<String, ComponentArtifact> artifacts;

    public PhpComponentArtifactRegistry() {
        Map<String, ComponentArtifact> loaded = new LinkedHashMap<>();
        COMPONENT_IDS.stream().sorted().forEach(id -> loaded.put(id, load(id)));
        this.artifacts = Collections.unmodifiableMap(loaded);
    }

    public Set<String> getComponentIds() {
        return artifacts.keySet();
    }

    public ComponentArtifact getRequired(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId不能为空");
        }
        ComponentArtifact artifact = artifacts.get(componentId.trim());
        if (artifact == null) {
            throw new IllegalArgumentException("PHP 组件不存在: " + componentId);
        }
        return artifact;
    }

    private ComponentArtifact load(String componentId) {
        String resource = "/components/" + componentId + ".php";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("PHP 组件资源不存在: " + resource);
            byte[] source = input.readAllBytes();
            String sourceText = new String(source, StandardCharsets.UTF_8);
            String declaredId = requiredMetadata(COMPONENT_ID, sourceText, "id", componentId);
            if (!componentId.equals(declaredId)) {
                throw new IllegalStateException("PHP 组件 ID 与文件名不一致: " + componentId + " != " + declaredId);
            }
            String version = requiredMetadata(COMPONENT_VERSION, sourceText, "version", componentId);
            return new ComponentArtifact(componentId, version, sha256(source), PuppetRuntime.PHP,
                    ComponentDeliveryMode.DISK_CACHE, source);
        } catch (IOException e) {
            throw new IllegalStateException("读取 PHP 组件失败: " + componentId, e);
        }
    }

    private String requiredMetadata(Pattern pattern, String source, String name, String componentId) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            throw new IllegalStateException("PHP 组件缺少 " + name + ": " + componentId);
        }
        return matcher.group(1).trim();
    }

    private String sha256(byte[] source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 初始化失败", e);
        }
    }
}
