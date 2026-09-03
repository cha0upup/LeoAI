package org.leo.core.puppet.capability;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.runtime.CapabilityStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable capability names exposed to UI and orchestration layers.
 */
public final class PuppetNodeCapabilityRegistry {

    private static final List<CapabilityDescriptor> DESCRIPTORS = List.of(
            descriptor("basicInfo", BasicInfoCapable.class),
            descriptor("command", CommandCapable.class),
            descriptor("terminal", TerminalCapable.class),
            descriptor("file", FileCapable.class),
            descriptor("networkInfo", NetworkInfoCapable.class),
            descriptor("sql", SqlCapable.class),
            descriptor("script", ScriptCapable.class),
            descriptor("resource", ResourceCapable.class),
            descriptor("httpSender", HttpSenderCapable.class),
            descriptor("process", ProcessCapable.class),
            descriptor("registry", RegistryCapable.class),
            descriptor("scheduledTask", ScheduledTaskCapable.class),
            descriptor("service", ServiceCapable.class),
            descriptor("eventLog", EventLogCapable.class),
            descriptor("firewall", FirewallCapable.class),
            descriptor("networkConnection", NetworkConnectionCapable.class),
            descriptor("userAccount", UserAccountCapable.class),
            descriptor("networkShare", NetworkShareCapable.class),
            descriptor("installedSoftware", InstalledSoftwareCapable.class),
            descriptor("persistence", PersistenceCapable.class),
            descriptor("docker", DockerCapable.class),
            descriptor("suidCapability", SuidCapabilityCapable.class),
            descriptor("httpProxy", HttpProxyCapable.class),
            descriptor("localForward", LocalForwardCapable.class),
            descriptor("reverseTunnel", ReverseTunnelCapable.class),
            descriptor("socks5Proxy", Socks5ProxyCapable.class),
            descriptor("networkProbe", NetworkProbeCapable.class),
            descriptor("componentInvoke", ComponentInvokeCapable.class),
            descriptor("componentManage", ComponentManageCapable.class),
            descriptor("webRuntimeManage", WebRuntimeManageCapable.class),
            descriptor("plugin", PluginCapable.class),
            descriptor("javaPlugin", JavaPluginCapable.class),
            descriptor("credentialHarvest", CredentialHarvestCapable.class)
    );

    private PuppetNodeCapabilityRegistry() {
    }

    public static List<CapabilityDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static List<String> listSupported(AbstractPuppetNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        List<String> supported = new ArrayList<>();
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (isSupported(node, descriptor)) {
                supported.add(descriptor.name());
            }
        }
        return supported;
    }

    public static boolean supports(AbstractPuppetNode node, String capabilityName) {
        if (node == null || capabilityName == null || capabilityName.isBlank()) {
            return false;
        }
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (descriptor.name().equals(capabilityName)) {
                return isSupported(node, descriptor);
            }
        }
        return false;
    }

    public static boolean supports(AbstractPuppetNode node, Class<?> capabilityType) {
        if (node == null || capabilityType == null) {
            return false;
        }
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (descriptor.type().equals(capabilityType)) {
                return isSupported(node, descriptor);
            }
        }
        return capabilityType.isInstance(node);
    }

    public static CapabilityStatus getStatus(AbstractPuppetNode node, String capabilityName) {
        if (node == null || capabilityName == null || capabilityName.isBlank()) {
            return null;
        }
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (!descriptor.name().equals(capabilityName) || !descriptor.type().isInstance(node)) {
                continue;
            }
            CapabilityStatus explicit = node.getCapabilitySet().get(capabilityName);
            return explicit != null ? explicit : CapabilityStatus.available(capabilityName);
        }
        return null;
    }

    public static List<CapabilityStatus> listStatuses(AbstractPuppetNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        List<CapabilityStatus> statuses = new ArrayList<>();
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            CapabilityStatus status = getStatus(node, descriptor.name());
            if (status != null) {
                statuses.add(status);
            }
        }
        return statuses;
    }

    public static String capabilityName(Class<?> capabilityType) {
        if (capabilityType == null) {
            return null;
        }
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (descriptor.type().equals(capabilityType)) {
                return descriptor.name();
            }
        }
        return null;
    }

    private static boolean isSupported(AbstractPuppetNode node, CapabilityDescriptor descriptor) {
        if (!descriptor.type().isInstance(node)) {
            return false;
        }
        return node.getCapabilitySet().isAvailable(descriptor.name(), true);
    }

    private static CapabilityDescriptor descriptor(String name, Class<?> type) {
        return new CapabilityDescriptor(name, type);
    }

    public record CapabilityDescriptor(String name, Class<?> type) {
    }
}
