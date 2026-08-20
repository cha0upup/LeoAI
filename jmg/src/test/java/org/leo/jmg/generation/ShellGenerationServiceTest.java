package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellGenerationServiceTest {

    private final ShellGenerationService service = new ShellGenerationService();

    @Test
    void webShellCommandCentralizesDefaultsAndMetadata() throws Exception {
        ShellGenerationOutcome outcome = service.generateWebShell(
                WebShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise(), " jsp ")
                        .payloadKey("service-test-key")
                        .obfuscationSteps(java.util.Collections.<String>emptyList())
                        .obfuscationSeed(301L)
                        .build());

        Map<String, Object> metadata = outcome.getMetadata();
        assertEquals("JSP", metadata.get("type"));
        assertEquals("http", metadata.get("protocol"));
        assertEquals("auto", metadata.get("targetJavaVersion"));
        assertEquals("javax", metadata.get("servletNamespace"));
        assertEquals("301", metadata.get("obfuscationSeed"));
        assertTrue(outcome.getContent().length() > 100);
        assertEquals(1, outcome.getClassArtifacts().size());
        assertEquals("core", outcome.getClassArtifacts().get(0).getRole());
        assertClassArtifact(outcome.getClassArtifacts().get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.put("protocol", "changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> outcome.getClassArtifacts().clear());
    }

    @Test
    void memoryShellCommandReturnsFinalNamesBytesAndSharedSummary() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                baseMemoryCommand()
                        .protocol("httpchunk")
                        .servletNamespace("jakarta")
                        .obfuscationSeed(302L)
                        .build());
        GenerationResult result = outcome.getGenerationResult();

        assertArrayEquals(result.getInjectorClassBytes(),
                Base64.getDecoder().decode(outcome.getContent()));
        assertEquals(result.getInjectorClassName(),
                outcome.getMetadata().get("injectorClassName"));
        assertEquals("httpchunk", outcome.getMetadata().get("protocol"));
        assertEquals("X-Test : secret", outcome.getMetadata().get("headerConfig"));
        assertFalse((Boolean) outcome.getMetadata().get("templateMutated"));
        assertEquals(1,
                ((List<?>) outcome.getMetadata().get("compatibilityWarnings")).size());
        assertEquals(Arrays.asList("core", "shell", "injector"),
                outcome.getClassArtifacts().stream()
                        .map(GeneratedClassArtifact::getRole)
                        .collect(java.util.stream.Collectors.toList()));
        for (GeneratedClassArtifact artifact : outcome.getClassArtifacts()) {
            assertClassArtifact(artifact);
        }
    }

    @Test
    void websocketCommandOwnsEndpointAndHeaderDefaults() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                        MemoryShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise())
                        .payloadKey("memory-test-key")
                        .serverType("Tomcat")
                        .injectorName("WebSocketInjector")
                        .packerType("DefaultBase64")
                        .protocol("websocket")
                        .obfuscationSeed(303L)
                        .build());

        assertEquals("/leo", outcome.getMetadata().get("urlPattern"));
        assertEquals("WebSocket endpoint: /leo",
                outcome.getMetadata().get("headerConfig"));
    }

    @Test
    void tomcatUpgradeReturnsExactActivationHeaders() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                        MemoryShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise())
                        .header("X-Test", "secret")
                        .payloadKey("memory-test-key")
                        .serverType("Tomcat")
                        .injectorName("UpgradeInjector")
                        .packerType("DefaultBase64")
                        .shellClassName("org.example.UpgradeEntry")
                        .obfuscationSeed(307L)
                        .build());

        assertEquals("Connection: Upgrade; Upgrade: org.example.UpgradeEntry",
                outcome.getMetadata().get("activationConfig"));
        assertEquals("X-Test : secret", outcome.getMetadata().get("headerConfig"));
    }

    @Test
    void commandSnapshotsObfuscationSteps() throws Exception {
        List<String> steps =
                new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));
        MemoryShellGenerationCommand command = baseMemoryCommand()
                .obfuscationSteps(steps)
                .obfuscationSeed(304L)
                .build();
        steps.clear();

        ShellGenerationOutcome outcome = service.generateMemoryShell(command);
        assertTrue(outcome.getContent().length() > 20);
    }

    @Test
    void jarPackerReceivesCoreShellAndInjectorEntries() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                baseMemoryCommand()
                        .packerType("JarBase64")
                        .lambdaSuffix(true)
                        .staticInitialize(true)
                        .shrink(false)
                        .obfuscationSeed(305L)
                        .build());

        JarInputStream input = new JarInputStream(new ByteArrayInputStream(
                Base64.getDecoder().decode(outcome.getContent())));
        List<String> entries = new ArrayList<String>();
        try {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                entries.add(entry.getName());
            }
        } finally {
            input.close();
        }
        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(name -> name.endsWith(".class")));
        assertTrue((Boolean) outcome.getMetadata().get("lambdaSuffix"));
        assertTrue((Boolean) outcome.getMetadata().get("staticInitialize"));
        assertFalse((Boolean) outcome.getMetadata().get("shrink"));
        assertNotNull(outcome.getMetadata().get("packingConfig"));
    }

    @Test
    void jetty5BuildsWithJavaxAndRejectsJakarta() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                        MemoryShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise())
                        .header("X-Test", "secret")
                        .payloadKey("memory-test-key")
                        .serverType("Jetty5")
                        .injectorName("FilterInjector")
                        .packerType("DefaultBase64")
                        .servletNamespace("javax")
                        .obfuscationSeed(306L)
                        .build());

        assertEquals("Jetty5", outcome.getMetadata().get("serverType"));
        assertNotNull(Base64.getDecoder().decode(outcome.getContent()));
        assertThrows(IllegalArgumentException.class, () ->
                                service.generateMemoryShell(
                        MemoryShellGenerationCommand.builder(
                                        requestDisguise(), responseDisguise())
                                .header("X-Test", "secret")
                                .payloadKey("memory-test-key")
                                .serverType("Jetty5")
                                .injectorName("FilterInjector")
                                .packerType("DefaultBase64")
                                .servletNamespace("jakarta")
                                .build()));
    }

    private static MemoryShellGenerationCommand.Builder baseMemoryCommand() {
        return MemoryShellGenerationCommand.builder(
                        requestDisguise(), responseDisguise())
                .header("X-Test", "secret")
                .payloadKey("memory-test-key")
                .serverType("Tomcat")
                .injectorName("FilterInjector")
                .packerType("DefaultBase64");
    }

    private static Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static void assertClassArtifact(GeneratedClassArtifact artifact) {
        byte[] bytes = Base64.getDecoder().decode(artifact.getContent());
        assertEquals(artifact.getSizeBytes(), bytes.length);
        assertEquals(64, artifact.getSha256().length());
        assertEquals("base64", artifact.getContentEncoding());
        assertEquals("application/java-vm", artifact.getMediaType());
        assertTrue(artifact.getEntryName().endsWith(".class"));
        assertTrue(artifact.getFileName().endsWith(".class"));
        assertTrue(bytes.length > 4);
        assertEquals((byte) 0xca, bytes[0]);
        assertEquals((byte) 0xfe, bytes[1]);
        assertEquals((byte) 0xba, bytes[2]);
        assertEquals((byte) 0xbe, bytes[3]);
        assertNotNull(artifact.getClassName());
    }
}
