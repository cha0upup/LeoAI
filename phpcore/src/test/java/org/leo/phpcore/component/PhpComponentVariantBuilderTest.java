package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.util.json.PortableJsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpComponentVariantBuilderTest {

    @Test
    void buildsStableEndpointSpecificAliasesAndSourceVariants() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        PhpComponentVariantBuilder builder = new PhpComponentVariantBuilder();
        ComponentArtifact base = registry.getRequired("BasicInfoComponent");

        ComponentArtifact first = builder.variant(base, "host-a");
        ComponentArtifact repeated = builder.variant(base, "host-a");
        ComponentArtifact second = builder.variant(base, "host-b");

        assertSame(first, repeated);
        assertTrue(first.getComponentId().matches("c[a-f0-9]{23}"));
        assertNotEquals(base.getComponentId(), first.getComponentId());
        assertNotEquals(first.getComponentId(), second.getComponentId());
        assertNotEquals(first.getDigest(), second.getDigest());
        String source = new String(first.getContent(), StandardCharsets.UTF_8);
        assertTrue(source.contains("'id' => '" + first.getComponentId() + "'"));
        assertFalse(source.contains("'id' => 'BasicInfoComponent'"));
        assertFalse(source.contains("$get ="));
        assertEquals("BasicInfoComponent",
                builder.originalId(first.getComponentId(), "host-a", registry.getComponentIds()));
        assertEquals(2, builder.cachedVariantCount());
    }

    @Test
    void variantCacheEvictsLeastRecentlyUsedEntries() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        ComponentArtifact base = registry.getRequired("BasicInfoComponent");
        PhpComponentVariantBuilder builder = new PhpComponentVariantBuilder(2);

        ComponentArtifact first = builder.variant(base, "host-a");
        ComponentArtifact second = builder.variant(base, "host-b");
        assertSame(first, builder.variant(base, "host-a"));
        builder.variant(base, "host-c");

        assertEquals(2, builder.cachedVariantCount());
        assertSame(first, builder.variant(base, "host-a"));
        assertNotEquals(second, builder.variant(base, "host-b"));
        assertEquals(2, builder.cachedVariantCount());
    }

    @Test
    void allGeneratedVariantsKeepValidPhpSyntax(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        PhpComponentVariantBuilder builder = new PhpComponentVariantBuilder();
        for (String componentId : registry.getComponentIds()) {
            ComponentArtifact variant = builder.variant(registry.getRequired(componentId), "syntax-host");
            Path file = directory.resolve(variant.getComponentId() + ".php");
            Files.write(file, variant.getContent());
            Process process = new ProcessBuilder("php", "-l", file.toString()).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), componentId + " lint timed out");
            assertEquals(0, process.exitValue(), componentId + ": " + new String(output, StandardCharsets.UTF_8));
        }
    }

    @Test
    void generatedVariantExecutesComponentHandler(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        ComponentArtifact variant = new PhpComponentVariantBuilder().variant(
                registry.getRequired("BasicInfoComponent"), "execution-host");
        Path file = directory.resolve("component.dat");
        Files.write(file, variant.getContent());
        String script = "$c=require $argv[1];echo json_encode(call_user_func($c['handle'],'get',array()));";
        Process process = new ProcessBuilder("php", "-r", script, file.toString())
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
        Map<String, Object> response = PortableJsonCodec.decode(output);
        assertEquals(200, ((Number) response.get("code")).intValue());
        assertTrue(response.containsKey("BasicInfo"));
    }

    @Test
    void diagnosticTextIsSplitInSourceAndPreservedAtRuntime(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        ComponentArtifact variant = new PhpComponentVariantBuilder().variant(
                registry.getRequired("ProcessComponent"), "diagnostic-host");
        String source = new String(variant.getContent(), StandardCharsets.UTF_8);
        assertFalse(source.contains("process command backend is unavailable"));
        assertFalse(source.contains("unsupported process action"));
        Path file = directory.resolve("process.dat");
        Files.write(file, variant.getContent());
        String script = "$c=require $argv[1];echo json_encode(call_user_func($c['handle'],'invalid',array()));";
        Process process = new ProcessBuilder("php", "-r", script, file.toString())
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
        Map<String, Object> response = PortableJsonCodec.decode(output);
        assertEquals("unsupported process action", response.get("msg"));
    }
}
