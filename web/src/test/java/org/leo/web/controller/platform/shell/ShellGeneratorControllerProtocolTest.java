package org.leo.web.controller.platform.shell;

import org.leo.jmg.generation.GeneratedClassArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.leo.service.generator.ScriptGeneratorService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShellGeneratorControllerProtocolTest {

    private final DisguiseManager disguiseManager = mock(DisguiseManager.class);
    private final ScriptGeneratorService scriptGeneratorService = mock(ScriptGeneratorService.class);
    private ShellGeneratorController controller;

    @BeforeEach
    void setUp() {
        controller = new ShellGeneratorController();
        ReflectionTestUtils.setField(controller, "disguiseManager", disguiseManager);
        ReflectionTestUtils.setField(controller, "scriptGeneratorService", scriptGeneratorService);
        when(scriptGeneratorService.getMetadata()).thenReturn(Map.of());
        when(disguiseManager.getDisguiseById("req")).thenReturn(requestDisguise());
        when(disguiseManager.getDisguiseById("resp")).thenReturn(responseDisguise());
    }

    @Test
    void exposesTransportCapabilityMatrix() {
        Map<?, ?> data = (Map<?, ?>) controller.getSupportedTypes().get("data");
        Map<?, ?> protocols = (Map<?, ?>) data.get("transportProtocols");

        assertEquals(List.of("http", "httpchunk"), protocols.get("webshell"));
        assertEquals(List.of("http", "httpchunk", "websocket"), protocols.get("memoryshell"));
        assertFalse(((List<?>) data.get("injectorCapabilities")).isEmpty());
        assertTrue(((List<?>) data.get("injectorCapabilities")).stream()
                .map(Map.class::cast)
                .anyMatch(item -> "TongWeb".equals(item.get("serverType"))
                        && "ValveInjector".equals(item.get("injectorName"))
                        && Boolean.TRUE.equals(item.get("requiresServerVersion"))));
        assertTrue(((List<?>) data.get("injectorCapabilities")).stream()
                .map(Map.class::cast)
                .anyMatch(item -> "TongWeb".equals(item.get("serverType"))
                        && "AgentFilterChain".equals(item.get("injectorName"))
                        && List.of("AgentJarBase64").equals(item.get("supportedPackers"))
                        && Boolean.FALSE.equals(item.get("supportsStaticInitialize"))
                        && Boolean.FALSE.equals(item.get("supportsUrlPattern"))));
        assertTrue(((List<?>) data.get("injectorCapabilities")).stream()
                .map(Map.class::cast)
                .anyMatch(item -> "Jetty".equals(item.get("serverType"))
                        && "HandlerInjector".equals(item.get("injectorName"))
                        && List.of("7-10", "11").equals(item.get("serverVersions"))));
        assertTrue(((List<?>) data.get("injectorCapabilities")).stream()
                .map(Map.class::cast)
                .anyMatch(item -> "Tomcat".equals(item.get("serverType"))
                        && "ByPassNginxWebSocketInjector".equals(item.get("injectorName"))
                        && Boolean.TRUE.equals(item.get("supportsHeaderGate"))));
        assertTrue(((List<?>) data.get("injectorCapabilities")).stream()
                .map(Map.class::cast)
                .anyMatch(item -> "Tomcat".equals(item.get("serverType"))
                        && "UpgradeInjector".equals(item.get("injectorName"))
                        && List.of("Connection: Upgrade", "Upgrade: ${shellClassName}")
                        .equals(item.get("activationHeaders"))));
        assertTrue(((Map<?, ?>) data.get("packerCompatibility"))
                .containsKey("AgentJarBase64"));
        assertTrue(((Map<?, ?>) data.get("memoryShellBuildOptions"))
                .containsKey("lambdaSuffix"));
    }

    @Test
    void generatesWebSocketMemoryArtifactWithoutHttpHeaderGuard() {
        HashMap<String, Object> response = controller.generateMemoryShell(params(
                "protocol", "websocket",
                "serverType", "Tomcat",
                "shellType", "WebSocketInjector",
                "packerType", "DefaultBase64",
                "urlPattern", "/socket"
        ));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals("websocket", data.get("protocol"));
        assertEquals("WebSocket endpoint: /socket", data.get("headerConfig"));
        assertFalse(String.valueOf(data.get("code")).isBlank());
        assertGeneratedClassArtifacts(data);
    }

    @Test
    void generatesHttpChunkMemoryAndRejectsWebSocketForJsp() {
        HashMap<String, Object> chunked = controller.generateMemoryShell(params(
                "protocol", "httpchunk",
                "serverType", "Tomcat",
                "shellType", "FilterInjector",
                "packerType", "DefaultBase64",
                "headerName", "X-Test",
                "headerValue", "secret",
                "targetJavaVersion", "9+",
                "lambdaSuffix", true,
                "staticInitialize", true,
                "shrink", false
        ));
        assertEquals(200, chunked.get("code"));
        Map<?, ?> chunkedData = (Map<?, ?>) chunked.get("data");
        assertEquals("httpchunk", chunkedData.get("protocol"));
        assertEquals(true, chunkedData.get("byPassJavaModule"));
        assertEquals(true, chunkedData.get("lambdaSuffix"));
        assertEquals(true, chunkedData.get("staticInitialize"));
        assertEquals(false, chunkedData.get("shrink"));
        assertTrue(String.valueOf(chunkedData.get("injectorClassName"))
                .contains("$Lambda$"));
        assertFalse(String.valueOf(chunkedData.get("code")).isBlank());
        assertGeneratedClassArtifacts(chunkedData);

        HashMap<String, Object> websocketJsp = controller.generateWebShell(params(
                "protocol", "websocket",
                "shellType", "JSP"
        ));
        assertEquals(400, websocketJsp.get("code"));
        assertTrue(String.valueOf(websocketJsp.get("msg")).contains("内存构建"));
    }

    @Test
    void generatesTomcatListenerWithoutLegacyServletApiOnWebClasspath() {
        HashMap<String, Object> generated = controller.generateMemoryShell(params(
                "protocol", "http",
                "serverType", "Tomcat",
                "shellType", "ListenerInjector",
                "packerType", "DefaultBase64",
                "servletNamespace", "jakarta",
                "headerName", "X-Test",
                "headerValue", "secret"
        ));

        assertEquals(200, generated.get("code"), String.valueOf(generated.get("msg")));
        Map<?, ?> data = (Map<?, ?>) generated.get("data");
        assertEquals("jakarta", data.get("servletNamespace"));
        assertGeneratedClassArtifacts(data);
    }

    @Test
    void tongWebValveRequiresAndReturnsServerVersion() {
        HashMap<String, Object> missingVersion = controller.generateMemoryShell(params(
                "serverType", "TongWeb",
                "shellType", "ValveInjector",
                "packerType", "DefaultBase64",
                "headerName", "X-Test",
                "headerValue", "secret"
        ));
        assertEquals(400, missingVersion.get("code"));

        HashMap<String, Object> generated = controller.generateMemoryShell(params(
                "serverType", "TongWeb",
                "serverVersion", "8",
                "shellType", "ValveInjector",
                "packerType", "DefaultBase64",
                "headerName", "X-Test",
                "headerValue", "secret"
        ));
        assertEquals(200, generated.get("code"));
        Map<?, ?> data = (Map<?, ?>) generated.get("data");
        assertEquals("8", data.get("serverVersion"));
    }

    private HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("reqDisguiseId", "req");
        params.put("respDisguiseId", "resp");
        params.put("respCode", 200);
        for (int i = 0; i < values.length; i += 2) {
            params.put(String.valueOf(values[i]), values[i + 1]);
        }
        return params;
    }

    private Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        disguise.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        disguise.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static void assertGeneratedClassArtifacts(Map<?, ?> data) {
        List<?> artifacts = (List<?>) data.get("classArtifacts");
        assertEquals(3, artifacts.size());
        GeneratedClassArtifact injector = (GeneratedClassArtifact) artifacts.get(2);
        assertEquals("injector", injector.getRole());
        assertEquals("base64", injector.getContentEncoding());
        assertEquals(64, injector.getSha256().length());
        assertTrue(injector.getSizeBytes() > 0);
    }
}
