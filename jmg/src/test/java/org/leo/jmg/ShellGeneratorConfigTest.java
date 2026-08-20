package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.jmg.catalog.GeneratorCatalog;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellGeneratorConfigTest {

    @Test
    void acceptsCanonicalProtocolNamesAndRejectsRemovedAliases() {
        assertEquals("websocket", builder().protocol(" WebSocket ").build().getProtocol());
        assertThrows(IllegalArgumentException.class,
                () -> builder().protocol("httpChunked"));
        assertThrows(IllegalArgumentException.class,
                () -> builder().protocol("HTTP-CHUNK"));
        assertThrows(IllegalArgumentException.class, () -> builder().protocol("ftp"));
    }

    @Test
    void enforcesProtocolBoundariesForWebAndMemoryArtifacts() {
        ShellGeneratorConfig websocketWebShell = builder().protocol("websocket").build();
        IllegalArgumentException webShellError = assertThrows(IllegalArgumentException.class,
                () -> validateWebShell(websocketWebShell));
        assertTrue(webShellError.getMessage().contains("内存构建"));

        // httpchunk 对外使用普通注入器形态名，生成器在内部选择 Chunk 模板。
        ShellGeneratorConfig chunkedMemory = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector")
                .build();
        assertDoesNotThrow(() -> validateInjector(chunkedMemory));

        ShellGeneratorConfig wrongWebSocketInjector = injectorBuilder()
                .protocol("websocket")
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(wrongWebSocketInjector));

        ShellGeneratorConfig websocket = injectorBuilder()
                .protocol("websocket")
                .shellType("WebSocketInjector")
                .urlPattern("/socket")
                .build();
        assertDoesNotThrow(() -> validateInjector(websocket));
    }

    @Test
    void validatesHttpChunkResponseCode() {
        ShellGeneratorConfig badResp = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector")
                .respCode(204)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(badResp));
    }

    @Test
    void resolvesPublicHttpChunkInjectorNamesToStrictChunkTemplates() {
        ShellGeneratorConfig filter = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector")
                .build();
        ShellGeneratorConfig valve = injectorBuilder()
                .protocol("httpchunk")
                .shellType("ValveInjector")
                .build();

        assertDoesNotThrow(() -> validateInjector(filter));
        assertDoesNotThrow(() -> validateInjector(valve));
        assertEquals("org.leo.jmg.mem.shell.http.LeoFilterChunkTpl",
                GeneratorCatalog.resolve(
                        "Tomcat", "FilterInjector", "httpchunk").getShellTemplateName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoValveChunkTpl",
                GeneratorCatalog.resolve(
                        "Tomcat", "ValveInjector", "httpchunk").getShellTemplateName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoListenerChunkTpl",
                GeneratorCatalog.resolve(
                        "InforSuite", "ListenerInjector", "httpchunk")
                        .getShellTemplateName());
    }

    @Test
    void requiresExplicitWebSocketProtocolAndValidatesEndpointPath() {
        ShellGeneratorConfig implicitHttp = injectorBuilder()
                .shellType("WebSocketInjector")
                .urlPattern("/socket")
                .build();
        assertEquals("http", implicitHttp.getProtocol());
        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(implicitHttp));

        ShellGeneratorConfig invalidPath = injectorBuilder()
                .protocol("websocket")
                .shellType("WebSocketInjector")
                .urlPattern("/*")
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(invalidPath));
    }

    @Test
    void requiresHeaderGuardForHttpMemoryArtifacts() {
        ShellGeneratorConfig missingHeader = builder()
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validateInjector(missingHeader));
        assertTrue(error.getMessage().contains("headerName"));
    }

    @Test
    void parsesServletNamespacesAndDefaultsToJavax() {
        assertEquals(ServletNamespace.AUTO, builder().build().getServletNamespace());
        assertEquals(ServletNamespace.JAVAX, builder().build().getEffectiveServletNamespace());
        assertEquals(ServletNamespace.JAKARTA,
                builder().servletNamespace("jakarta").build().getEffectiveServletNamespace());
        assertThrows(IllegalArgumentException.class,
                () -> builder().servletNamespace("unsupported"));
        assertThrows(IllegalArgumentException.class,
                () -> validateWebShell(builder()
                        .header("X-Test", "secret")
                        .servletNamespace("jakarta")
                        .targetJavaVersion("7")
                        .build()));
    }

    @Test
    void fixedSeedReproducesGeneratedNames() {
        ShellGeneratorConfig first = builder().obfuscationSeed(42L).build();
        ShellGeneratorConfig second = builder().obfuscationSeed(42L).build();

        assertEquals(42L, first.getObfuscationSeed());
        assertEquals(first.getCoreClassName(), second.getCoreClassName());
        assertEquals(first.getMethodAction(), second.getMethodAction());
        assertEquals(first.getFieldParams(), second.getFieldParams());
    }

    @Test
    void tongWebValveRequiresKnownMajorVersion() {
        ShellGeneratorConfig.Builder tongWebValve = builder()
                .header("X-Test", "secret")
                .serverType("TongWeb")
                .shellType("ValveInjector")
                .packerType("DefaultBase64");

        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(tongWebValve.build()));
        assertDoesNotThrow(() -> validateInjector(tongWebValve.serverVersion("6").build()));
        assertDoesNotThrow(() -> validateInjector(tongWebValve.serverVersion("7").build()));
        assertDoesNotThrow(() -> validateInjector(tongWebValve.serverVersion("8").build()));
        assertThrows(IllegalArgumentException.class,
                () -> validateInjector(tongWebValve.serverVersion("9").build()));
    }

    private ShellGeneratorConfig.Builder builder() {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] data){return data;}");
        return ShellGeneratorConfig.builder(request, response).payloadKey("config-test-key");
    }

    private ShellGeneratorConfig.Builder injectorBuilder() {
        return builder()
                .header("X-Test", "secret")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .urlPattern("/*");
    }

    private static void validateInjector(ShellGeneratorConfig config) {
        GenerationPlan.forInjector(GenerationRequest.from(config));
    }

    private static void validateWebShell(ShellGeneratorConfig config) {
        GenerationPlan.forWebShell(
                GenerationRequest.from(config),
                GenerationPlan.ArtifactKind.JSP);
    }
}
