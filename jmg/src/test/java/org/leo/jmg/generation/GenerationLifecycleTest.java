package org.leo.jmg.generation;

import javassist.ClassPool;
import javassist.CtClass;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TransportProtocol;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationLifecycleTest {

    @Test
    void requestSnapshotsInputAtGeneratorBoundary() {
        ShellGeneratorConfig config = injectorConfig()
                .customJspTemplate("before")
                .build();

        GenerationRequest request = GenerationRequest.from(config);
        config.getReqDisguise().setTrafficDecodeBody("changed-after-snapshot");

        assertEquals("before", request.getCustomJspTemplate());
        assertFalse(request.isBypassJavaModule());
        assertEquals(TransportProtocol.HTTP_CHUNK, request.getProtocol());
        assertFalse("changed-after-snapshot".equals(
                request.createRequestDisguiseSnapshot().getTrafficDecodeBody()));
        assertThrows(UnsupportedOperationException.class,
                () -> request.getJspObfuscationSteps().add("another-step"));
    }

    @Test
    void planResolvesCatalogPackerAndDerivedFlagsOnce() {
        GenerationPlan plan = GenerationPlan.forInjector(
                GenerationRequest.from(injectorConfig().build()));

        assertEquals(GenerationPlan.ArtifactKind.INJECTOR, plan.getArtifactKind());
        assertEquals("FilterInjector",
                plan.getInjectorDescriptor().getInjectorName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoFilterChunkTpl",
                plan.getInjectorDescriptor().getShellTemplateName());
        assertNotNull(plan.getPacker());
        assertFalse(plan.isAbstractTranslet());
    }

    @Test
    void completePipelineReturnsAllFinalArtifacts() throws Exception {
        GenerationRequest request = GenerationRequest.from(injectorConfig()
                .obfuscationSeed(42L)
                .build());

        GenerationResult result =
                new ShellGenerator(request).generateFormattedInjector();

        assertNotNull(result.getCoreClassBytes());
        assertNotNull(result.getShellClassBytes());
        assertNotNull(result.getInjectorClassBytes());
        assertArrayEquals(result.getInjectorClassBytes(),
                Base64.getDecoder().decode(result.getContent()));
    }

    @Test
    void generatorUsesImmutableRequestSnapshot() throws Exception {
        ShellGeneratorConfig config = injectorConfig()
                .obfuscationSeed(84L)
                .build();
        GenerationRequest request = GenerationRequest.from(config);
        config.getReqDisguise().setTrafficDecodeBody(null);

        GenerationResult result =
                new ShellGenerator(request).generateFormattedInjector();

        assertNotNull(Base64.getDecoder().decode(result.getContent()));
        assertNotNull(result.getInjectorClassName());
    }

    @Test
    void generatorCanBeReusedAndCalledConcurrently() throws Exception {
        GenerationRequest request = GenerationRequest.from(injectorConfig()
                .obfuscationSeed(126L)
                .build());
        ShellGenerator generator = new ShellGenerator(request);

        GenerationResult first = generator.generateFormattedInjector();
        GenerationResult second = generator.generateFormattedInjector();
        assertEquivalent(first, second);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Callable<GenerationResult> task = generator::generateFormattedInjector;
            List<Future<GenerationResult>> futures =
                    executor.invokeAll(Arrays.asList(task, task, task));
            for (Future<GenerationResult> future : futures) {
                assertEquivalent(first, future.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lambdaModuleBypassAndStaticInitializationAreAppliedToInjector() throws Exception {
        GenerationRequest request = GenerationRequest.from(injectorConfig()
                .targetJavaVersion("9+")
                .shellClassName("sample.Payload")
                .injectorClassName("sample.Loader")
                .lambdaSuffix(true)
                .staticInitialize(true)
                .obfuscationSeed(127L)
                .build());

        assertFalse(request.isBypassJavaModule());
        assertTrue(request.isBypassJavaModuleEffective());

        GenerationResult result = new ShellGenerator(request)
                .generateFormattedInjector();
        assertEquals("sample.Payload$Proxy0$$Lambda$1",
                result.getShellClassName());
        assertEquals("sample.Loader$Proxy0$$Lambda$1",
                result.getInjectorClassName());

        CtClass injector = new ClassPool(null).makeClass(
                new ByteArrayInputStream(result.getInjectorClassBytes()));
        try {
            assertNotNull(injector.getDeclaredMethod("bypassJavaModule"));
            assertNotNull(injector.getClassInitializer());
        } finally {
            injector.detach();
        }
    }

    @Test
    void agentMountRejectsMisleadingStaticInitialization() {
        assertThrows(IllegalArgumentException.class, () ->
                GenerationPlan.forInjector(GenerationRequest.from(injectorConfig()
                        .protocol("http")
                        .shellType("AgentFilterChain")
                        .packerType("AgentJarBase64")
                        .staticInitialize(true)
                        .build())));
    }

    private static void assertEquivalent(GenerationResult expected,
                                         GenerationResult actual) {
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getCoreClassName(), actual.getCoreClassName());
        assertEquals(expected.getShellClassName(), actual.getShellClassName());
        assertEquals(expected.getInjectorClassName(), actual.getInjectorClassName());
        assertArrayEquals(expected.getCoreClassBytes(), actual.getCoreClassBytes());
        assertArrayEquals(expected.getShellClassBytes(), actual.getShellClassBytes());
        assertArrayEquals(expected.getInjectorClassBytes(), actual.getInjectorClassBytes());
    }

    private static ShellGeneratorConfig.Builder injectorConfig() {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return ShellGeneratorConfig.builder(request, response)
                .payloadKey("lifecycle-test-key")
                .protocol("httpchunk")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .header("X-Test", "secret")
                .urlPattern("/*")
                .jspObfuscationSteps(Arrays.asList("CHUNK_PAYLOAD"));
    }
}
