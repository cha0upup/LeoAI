package org.leo.jmg.catalog;

import org.junit.jupiter.api.Test;
import org.leo.jmg.TransportProtocol;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorCatalogTest {

    @Test
    void publicMetadataKeepsTheSharedContract() {
        Map<String, Object> metadata = GeneratorCatalog.getMetadata();
        assertEquals(new HashSet<String>(java.util.Arrays.asList(
                "serverInjectorTypes", "serverProtocolInjectorTypes", "injectorCapabilities",
                "packerTypes", "packerObfuscationSteps", "packerCompatibility", "packerAvailability",
                "targetJavaVersions", "servletNamespaces", "transportProtocols")), metadata.keySet());
        assertEquals(java.util.Arrays.asList("auto", "6", "7", "8", "9+", "17+"),
                metadata.get("targetJavaVersions"));
    }

    @Test
    void everyDescriptorIsUniqueAndRoundTripsThroughResolver() {
        Set<String> keys = new HashSet<String>();

        for (InjectorDescriptor descriptor : GeneratorCatalog.getAllDescriptors()) {
            String key = descriptor.getServerType().getValue() + "|"
                    + descriptor.getProtocol().getValue() + "|"
                    + descriptor.getInjectorName();

            assertTrue(keys.add(key), "目录项不能重复: " + key);
            assertSame(descriptor, GeneratorCatalog.resolve(
                    descriptor.getServerType().getValue(),
                    descriptor.getInjectorName(),
                    descriptor.getProtocol().getValue()));
        }
        assertFalse(keys.isEmpty());
    }

    @Test
    void everyDescriptorReferencesExistingTemplateBytes() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (InjectorDescriptor descriptor : GeneratorCatalog.getAllDescriptors()) {
            String shellResource =
                    descriptor.getShellTemplateName().replace('.', '/') + ".class";
            String injectorResource =
                    descriptor.getInjectorTemplateName().replace('.', '/') + ".class";

            assertResourceExists(loader, shellResource);
            assertResourceExists(loader, injectorResource);
        }
    }

    @Test
    void everyInjectorTemplateClassIsRegisteredInTheCatalog() throws Exception {
        Set<String> registered = new HashSet<String>();
        for (InjectorDescriptor descriptor : GeneratorCatalog.getAllDescriptors()) {
            registered.add(descriptor.getInjectorTemplateName());
        }

        Set<String> discovered = new HashSet<String>();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(
                "classpath*:org/leo/jmg/mem/injectortpl/**/*.class");
        String marker = "org/leo/jmg/mem/injectortpl/";
        for (Resource resource : resources) {
            String uri = resource.getURI().toString();
            int markerIndex = uri.lastIndexOf(marker);
            if (markerIndex < 0 || !uri.endsWith(".class")) {
                continue;
            }
            String className = uri.substring(markerIndex, uri.length() - 6)
                    .replace('/', '.');
            if (className.indexOf('$') < 0
                    && !className.endsWith(".InjectorGenerator")
                    && !className.endsWith("Test")) {
                discovered.add(className);
            }
        }

        assertEquals(discovered, registered,
                "注入器模板目录与能力目录必须保持一一对应");
    }

    @Test
    void protocolMetadataComesFromResolvableCatalogEntries() {
        Map<String, Map<String, List<String>>> matrix =
                GeneratorCatalog.getProtocolInjectorMap();

        for (Map.Entry<String, Map<String, List<String>>> protocolEntry : matrix.entrySet()) {
            for (Map.Entry<String, List<String>> serverEntry
                    : protocolEntry.getValue().entrySet()) {
                for (String injectorName : serverEntry.getValue()) {
                    assertNotNull(GeneratorCatalog.resolve(
                            serverEntry.getKey(), injectorName, protocolEntry.getKey()));
                }
            }
        }
        assertEquals(TransportProtocol.values().length, matrix.size());
        assertThrows(UnsupportedOperationException.class,
                () -> matrix.put("invalid", null));
    }

    @Test
    void httpChunkNeverFallsBackToOrdinaryHttpTemplate() {
        InjectorDescriptor filter =
                GeneratorCatalog.resolve("Tomcat", "FilterInjector", "httpchunk");
        InjectorDescriptor valve =
                GeneratorCatalog.resolve("Tomcat", "ValveInjector", "httpchunk");

        assertNotNull(filter);
        assertNotNull(valve);
        assertEquals("org.leo.jmg.mem.shell.http.LeoFilterChunkTpl",
                filter.getShellTemplateName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoValveChunkTpl",
                valve.getShellTemplateName());
        assertNotNull(GeneratorCatalog.resolve(
                "InforSuite", "ListenerInjector", "httpchunk"));
        assertNull(GeneratorCatalog.resolve(
                "SpringWebMVC", "InterceptorInjector", "httpchunk"));
    }

    @Test
    void correctedMappingsUseMountCompatibleShellTemplates() {
        assertEquals("org.leo.jmg.mem.shell.http.LeoControllerHandlerTpl",
                GeneratorCatalog.resolve("SpringWebMVC", "ControllerHandlerInjector", "http")
                        .getShellTemplateName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoJettyCustomizerTpl",
                GeneratorCatalog.resolve("Jetty", "CustomizerInjector", "http")
                        .getShellTemplateName());
        assertNotNull(GeneratorCatalog.resolve("InforSuite", "ValveInjector", "http"));
        assertNotNull(GeneratorCatalog.resolve("Struts2", "ActionInjector", "http"));
        InjectorDescriptor handler = GeneratorCatalog.resolve(
                "Jetty", "HandlerInjector", "httpchunk");
        assertNotNull(handler);
        assertEquals(MountType.HANDLER, handler.getMountType());
        assertEquals("org.leo.jmg.mem.shell.http.LeoJettyHandlerChunkTpl",
                handler.getShellTemplateName());
        assertEquals(java.util.Arrays.asList("7-10", "11"),
                handler.getSupportedServerVersions());
        assertFalse(handler.supportsUrlPattern());
    }

    @Test
    void tongWebValvePublishesVersionRequirement() {
        InjectorDescriptor descriptor = GeneratorCatalog.resolve(
                "TongWeb", "ValveInjector", "http");

        assertNotNull(descriptor);
        assertTrue(descriptor.requiresServerVersion());
        assertEquals(java.util.Arrays.asList("6", "7", "8"),
                descriptor.getSupportedServerVersions());
        assertTrue(GeneratorCatalog.getCapabilityDescriptors().stream()
                .anyMatch(item -> "TongWeb".equals(item.get("serverType"))
                        && "ValveInjector".equals(item.get("injectorName"))
                        && Boolean.TRUE.equals(item.get("requiresServerVersion"))));
    }

    @Test
    void tongWebAgentMountsPublishAgentJarBoundary() {
        InjectorDescriptor filterChain = GeneratorCatalog.resolve(
                "TongWeb", "AgentFilterChain", "http");
        InjectorDescriptor contextValve = GeneratorCatalog.resolve(
                "TongWeb", "AgentContextValve", "httpchunk");

        assertNotNull(filterChain);
        assertNotNull(contextValve);
        assertEquals(MountType.AGENT_FILTER_CHAIN, filterChain.getMountType());
        assertEquals(MountType.AGENT_CONTEXT_VALVE, contextValve.getMountType());
        assertEquals(java.util.Collections.singletonList("AgentJarBase64"),
                filterChain.getSupportedPackers());
        assertEquals("org.leo.jmg.mem.shell.http.LeoAgentChunkTpl",
                contextValve.getShellTemplateName());
        assertFalse(filterChain.supportsStaticInitialize());
        assertFalse(filterChain.supportsUrlPattern());
        for (String name : java.util.Arrays.asList("AgentFilterChain", "AgentContextValve")) {
            assertTrue(GeneratorCatalog.getCapabilityDescriptors().stream()
                    .anyMatch(item -> "TongWeb".equals(item.get("serverType"))
                            && name.equals(item.get("injectorName"))
                            && java.util.Collections.singletonList("AgentJarBase64")
                            .equals(item.get("supportedPackers"))));
        }
        assertTrue(GeneratorCatalog.getCapabilityDescriptors().stream()
                .anyMatch(item -> "TongWeb".equals(item.get("serverType"))
                        && "AgentFilterChain".equals(item.get("injectorName"))
                        && Boolean.FALSE.equals(item.get("supportsStaticInitialize"))
                        && Boolean.FALSE.equals(item.get("supportsUrlPattern"))));
    }

    @Test
    void pathMetadataOnlyAppearsForMappedMounts() {
        assertTrue(GeneratorCatalog.resolve("Tomcat", "FilterInjector", "http")
                .supportsUrlPattern());
        assertTrue(GeneratorCatalog.resolve("Tomcat", "WebSocketInjector", "websocket")
                .supportsUrlPattern());
        assertFalse(GeneratorCatalog.resolve("Tomcat", "ValveInjector", "http")
                .supportsUrlPattern());
        assertFalse(GeneratorCatalog.resolve("Jetty", "CustomizerInjector", "http")
                .supportsUrlPattern());
        InjectorDescriptor byPass = GeneratorCatalog.resolve(
                "Tomcat", "ByPassNginxWebSocketInjector", "websocket");
        assertNotNull(byPass);
        assertEquals(MountType.WEBSOCKET, byPass.getMountType());
        assertTrue(byPass.supportsHeaderGate());
        InjectorDescriptor upgrade = GeneratorCatalog.resolve(
                "Tomcat", "UpgradeInjector", "httpchunk");
        assertNotNull(upgrade);
        assertEquals(MountType.UPGRADE, upgrade.getMountType());
        assertEquals("org.leo.jmg.mem.shell.http.LeoAgentChunkTpl",
                upgrade.getShellTemplateName());
        assertFalse(upgrade.supportsUrlPattern());
        assertTrue(GeneratorCatalog.getCapabilityDescriptors().stream()
                .anyMatch(item -> "Tomcat".equals(item.get("serverType"))
                        && "UpgradeInjector".equals(item.get("injectorName"))
                        && java.util.Arrays.asList("Connection: Upgrade", "Upgrade: ${shellClassName}")
                        .equals(item.get("activationHeaders"))));
    }

    @Test
    void containerAgentMountMatrixMatchesReferenceCatalog() {
        Object[][] expected = new Object[][]{
                {"Tomcat", "AgentFilterChain"},
                {"Tomcat", "AgentContextValve"},
                {"JBoss", "AgentFilterChain"},
                {"Glassfish", "AgentContextValve"},
                {"Payara", "AgentFilterChain"},
                {"InforSuite", "AgentContextValve"},
                {"Jetty", "AgentHandler"},
                {"Jetty5", "AgentHandler"},
                {"Undertow", "AgentServletHandler"},
                {"JBossEAP7", "AgentServletHandler"},
                {"Wildfly", "AgentServletHandler"},
                {"Resin", "AgentFilterChain"},
                {"WebLogic", "AgentServletContext"},
                {"WebSphere", "AgentFilterManager"},
                {"SpringWebMVC", "AgentFrameworkServlet"},
                {"Apusic", "AgentFilterChain"},
                {"BES", "AgentContextValve"}
        };
        for (Object[] row : expected) {
            InjectorDescriptor descriptor = GeneratorCatalog.resolve(
                    String.valueOf(row[0]), String.valueOf(row[1]), "http");
            assertNotNull(descriptor, row[0] + " 缺少 " + row[1]);
            assertEquals(java.util.Collections.singletonList("AgentJarBase64"),
                    descriptor.getSupportedPackers());
            assertNotNull(GeneratorCatalog.resolve(
                    String.valueOf(row[0]), String.valueOf(row[1]), "httpchunk"));
        }
    }

    @Test
    void javaxOnlyServerFamiliesDeclareTheirNamespaceBoundary() {
        InjectorDescriptor jetty5 = GeneratorCatalog.resolve(
                "Jetty5", "FilterInjector", "http");
        InjectorDescriptor resin2 = GeneratorCatalog.resolve(
                "Resin2", "ServletInjector", "httpchunk");

        assertNotNull(jetty5);
        assertNotNull(resin2);
        assertEquals(MountType.FILTER, jetty5.getMountType());
        assertEquals(java.util.Collections.singletonList("javax"),
                jetty5.getSupportedServletNamespaces());
        assertEquals(MountType.SERVLET, resin2.getMountType());
        assertTrue(GeneratorCatalog.getCapabilityDescriptors().stream()
                .anyMatch(item -> "Jetty5".equals(item.get("serverType"))));
    }

    private static void assertResourceExists(ClassLoader loader, String resource) throws Exception {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            assertNotNull(input, "目录引用的模板不存在: " + resource);
        }
    }
}
