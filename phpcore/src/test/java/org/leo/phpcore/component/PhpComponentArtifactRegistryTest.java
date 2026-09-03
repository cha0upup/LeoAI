package org.leo.phpcore.component;

import org.junit.jupiter.api.Test;
import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpComponentArtifactRegistryTest {

    @Test
    void loadsIndependentDigestAddressedArtifacts() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        assertEquals(24, registry.getComponentIds().size());
        assertTrue(registry.getComponentIds().contains("ExecCommandComponent"));
        assertTrue(registry.getComponentIds().contains("HttpRequestComponent"));
        assertTrue(registry.getComponentIds().contains("ProxyForwardComponent"));
        assertTrue(registry.getComponentIds().contains("ReverseTunnelComponent"));
        assertTrue(registry.getComponentIds().contains("ProcessComponent"));
        assertTrue(registry.getComponentIds().contains("NetworkInfoComponent"));
        assertTrue(registry.getComponentIds().contains("NetworkConnectionComponent"));
        assertTrue(registry.getComponentIds().contains("NetworkProbeComponent"));
        assertTrue(registry.getComponentIds().contains("ServiceComponent"));
        assertTrue(registry.getComponentIds().contains("ScheduledTaskComponent"));
        assertTrue(registry.getComponentIds().contains("RegistryComponent"));
        assertTrue(registry.getComponentIds().contains("EventLogComponent"));
        assertTrue(registry.getComponentIds().contains("FirewallComponent"));
        assertTrue(registry.getComponentIds().contains("UserAccountComponent"));
        ComponentArtifact artifact = registry.getRequired("FileComponent");
        assertEquals(ComponentDeliveryMode.DISK_CACHE, artifact.getDeliveryMode());
        assertEquals("1.0.0", artifact.getVersion());
        assertEquals(64, artifact.getDigest().length());
        String source = new String(artifact.getContent(), StandardCharsets.UTF_8);
        assertTrue(source.startsWith("<?php"));
        assertTrue(source.contains("'id' => 'FileComponent'"));
        assertThrows(IllegalArgumentException.class, () -> registry.getRequired("MissingComponent"));

        assertEquals("2.2.0", registry.getRequired("DatabaseComponent").getVersion());
        assertEquals("2.3.0", registry.getRequired("ExecCommandComponent").getVersion());
        assertEquals("1.1.0", registry.getRequired("BasicInfoComponent").getVersion());
    }

    @Test
    void componentSourcesKeepPhp56SyntaxBaseline() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        for (String componentId : registry.getComponentIds()) {
            String source = new String(registry.getRequired(componentId).getContent(), StandardCharsets.UTF_8);
            assertFalse(source.matches("(?s).*function\\s*\\([^)]*\\b(?:string|int|bool|float)\\s+\\$.*"),
                    componentId + " contains PHP 7 parameter types");
            assertFalse(source.matches("(?s).*function\\s*\\([^)]*\\)\\s*:\\s*(?:void|bool|string|array).*"),
                    componentId + " contains PHP 7 return types");
            assertFalse(source.contains("PHP_OS_FAMILY,"), componentId + " uses an unsafe PHP_OS_FAMILY fallback");
            assertFalse(source.contains("leo_array_get"), componentId + " depends on the bootstrap getter");
            assertFalse(source.contains("leo_function_available"), componentId + " depends on bootstrap capability helpers");
            assertFalse(source.contains("leo_os_family"), componentId + " depends on bootstrap OS helpers");
        }
    }

    @Test
    void statefulComponentsAvoidStableProductAndWorkerMarkers() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        for (String componentId : registry.getComponentIds()) {
            String source = new String(registry.getRequired(componentId).getContent(), StandardCharsets.UTF_8);
            assertFalse(source.contains(".leo-php-"), componentId + " exposes a fixed state directory marker");
            assertFalse(source.contains("--leo-"), componentId + " exposes a fixed worker argument marker");
            assertFalse(source.contains("leo-cron-"), componentId + " exposes a fixed temporary prefix");
            assertFalse(source.contains("leo_reg_"), componentId + " exposes a fixed temporary prefix");
        }
    }

    @Test
    void statefulComponentsUseOpaqueOnDiskFileNamesAndBoundedStatusWrites() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        for (String componentId : new String[]{"NetworkProbeComponent", "ProxyForwardComponent",
                "ReverseTunnelComponent", "ExecCommandComponent"}) {
            String source = new String(registry.getRequired(componentId).getContent(), StandardCharsets.UTF_8);
            assertFalse(source.contains("config.json"), componentId + " exposes its configuration file role");
            assertFalse(source.contains("status.json"), componentId + " exposes its status file role");
            assertFalse(source.contains("in.queue"), componentId + " exposes its input queue role");
            assertFalse(source.contains("out.queue"), componentId + " exposes its output queue role");
        }
        String scan = new String(registry.getRequired("NetworkProbeComponent").getContent(), StandardCharsets.UTF_8);
        assertTrue(scan.contains("NetworkProbeComponent"));
        String proxy = new String(registry.getRequired("ProxyForwardComponent").getContent(), StandardCharsets.UTF_8);
        assertFalse(proxy.contains("time() % 5"));
        assertTrue(proxy.contains("time() - $lastStatusWrite >= 5"));
        assertTrue(proxy.contains("$limit = 8388608"));
        assertTrue(proxy.contains("count($entries) >= 128"));

        String reverse = new String(registry.getRequired("ReverseTunnelComponent").getContent(), StandardCharsets.UTF_8);
        assertTrue(reverse.contains("$limit = 8388608"));
        assertTrue(reverse.contains("count($clients) >= 256"));
        assertTrue(reverse.contains("count($entries) >= 32"));

        assertTrue(scan.contains("count($targets) > 128"));

        String terminal = new String(registry.getRequired("ExecCommandComponent").getContent(), StandardCharsets.UTF_8);
        assertTrue(terminal.contains("@rename($temporary, $path)"));
    }

    @Test
    void linuxInspectionPrefersNativeProcSources() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        String process = new String(registry.getRequired("ProcessComponent").getContent(), StandardCharsets.UTF_8);
        assertTrue(process.contains("glob('/proc/[0-9]*'"));
        assertTrue(process.contains("function_exists('posix_kill')"));
        assertFalse(process.contains("ss -lntup"));

        String network = new String(registry.getRequired("NetworkConnectionComponent").getContent(), StandardCharsets.UTF_8);
        assertTrue(network.contains("/proc/net/tcp"));
        assertTrue(network.contains("source=/proc/net"));
        assertTrue(network.contains("$linuxCommand"), "command fallback must remain for restricted proc mounts");
    }
}
