package org.leo.jmg.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;

import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPipelineTest {

    @Test
    void corePipelineProducesClassFileBytes() throws Exception {
        GenerationRequest request = webRequest();

        byte[] bytes = new CoreGenerationPipeline(request).generate();

        assertTrue(bytes.length > 4);
        assertEquals(0xCA, bytes[0] & 0xff);
        assertEquals(0xFE, bytes[1] & 0xff);
        assertEquals(0xBA, bytes[2] & 0xff);
        assertEquals(0xBE, bytes[3] & 0xff);
    }

    @Test
    void webPipelineOwnsJspAndJspxGeneration() throws Exception {
        GenerationRequest request = webRequest();
        WebShellGenerationPipeline pipeline =
                new WebShellGenerationPipeline(
                        request, new CoreGenerationPipeline(request));

        String jsp = pipeline.generate(GenerationPlan.ArtifactKind.JSP).getContent();
        String jspx = pipeline.generate(GenerationPlan.ArtifactKind.JSPX).getContent();

        assertTrue(jsp.length() > 100);
        assertTrue(jspx.length() > 100);
        assertTrue(jspx.contains("jsp:root"));
    }

    @Test
    void memoryAndPackingPipelinesComposeThroughWorkspace() throws Exception {
        GenerationRequest request = injectorRequest();
        GenerationPlan plan = GenerationPlan.forInjector(request);
        GenerationWorkspace workspace = GenerationWorkspace.create(request);
        MemoryShellGenerationPipeline memory =
                new MemoryShellGenerationPipeline(
                        request, workspace, new CoreGenerationPipeline(request));
        PackingPipeline packing = new PackingPipeline(request);

        String packed;
        byte[] generated;
        try (GenerationRandom.Scope ignored =
                     GenerationRandom.withSeed(request.getObfuscationSeed())) {
            generated = memory.generate(plan);
            packed = packing.pack(plan, workspace);
        }

        assertArrayEquals(generated, workspace.getInjectorClassBytes());
        assertArrayEquals(generated, Base64.getDecoder().decode(packed));
    }

    @Test
    void memoryPipelineRejectsWebShellPlan() {
        GenerationRequest request = injectorRequest();
        GenerationWorkspace workspace = GenerationWorkspace.create(request);
        MemoryShellGenerationPipeline pipeline =
                new MemoryShellGenerationPipeline(
                        request, workspace, new CoreGenerationPipeline(request));
        GenerationPlan webPlan =
                GenerationPlan.forWebShell(
                        request, GenerationPlan.ArtifactKind.JSP);

        assertThrows(IllegalArgumentException.class,
                () -> pipeline.generate(webPlan));
    }

    private static GenerationRequest webRequest() {
        return GenerationRequest.from(baseBuilder()
                .protocol("httpchunk")
                .jspObfuscationSteps(Collections.<String>emptyList())
                .obfuscationSeed(101L)
                .build());
    }

    private static GenerationRequest injectorRequest() {
        return GenerationRequest.from(baseBuilder()
                .protocol("httpchunk")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .header("X-Test", "pipeline")
                .urlPattern("/*")
                .obfuscationSeed(102L)
                .build());
    }

    private static ShellGeneratorConfig.Builder baseBuilder() {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return ShellGeneratorConfig.builder(request, response)
                .payloadKey("pipeline-test-key")
                .header("X-Test", "pipeline");
    }
}
