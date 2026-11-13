package org.leo.web.adapter.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.generator.ScriptGeneratorProvider;
import org.leo.core.runtime.AgentRuntime;
import org.leo.jmg.generation.WebShellWrapperContract;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.generator.ScriptGeneratorService;
import org.leo.service.shell.ShellResultStore;
import org.leo.service.shell.WebShellWrapperTemplateStore;
import org.leo.web.controller.platform.shell.ShellGeneratorController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ShellGeneratorToolsTest {

    @Test
    void httpAndAiExposeTheSameSharedMetadata() {
        ScriptGeneratorService generators = new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>())));
        ShellResultStore results = new ShellResultStore();
        Map<String, Object> ai = tools(mockDisguiseService(), results, generators).getShellGeneratorMeta();
        ShellGeneratorController controller = new ShellGeneratorController(
                mock(DisguiseManager.class), results, generators);
        Map<?, ?> http = (Map<?, ?>) controller.getSupportedTypes().get("data");

        for (String key : List.of("serverInjectorTypes", "serverProtocolInjectorTypes", "injectorCapabilities",
                "packerTypes", "packerObfuscationSteps", "packerCompatibility", "packerAvailability",
                "targetJavaVersions", "servletNamespaces", "transportProtocols", "runtimeGenerators")) {
            assertTrue(ai.containsKey(key), key);
            assertTrue(http.containsKey(key), key);
            assertEquals(http.get(key), ai.get(key), key);
        }
        assertTrue(((Map<?, ?>) ai.get("runtimeGenerators")).containsKey("php"));
        assertEquals(controller.getObfuscationSteps().get("data"), ai.get("obfuscationSteps"));
    }

    @Test
    void generatesCachedPhpResult() throws Exception {
        AtomicReference<GenerationRequest> captured = new AtomicReference<>();
        ScriptGeneratorService generators = new ScriptGeneratorService(List.of(
                phpProvider(captured)));
        ShellResultStore resultStore = new ShellResultStore();
        DisguiseService disguiseService = mockDisguiseService();
        ShellGeneratorTools tools = tools(disguiseService, resultStore, generators);

        Map<String, Object> result = tools.generatePhpWebShell(
                "req", "resp", "http", "portable", "X-Test", "secret", 202, "php-test-key", "seed-a");

        assertEquals(true, result.get("success"));
        assertNotNull(result.get("resultId"));
        assertEquals("<?php echo 'fixture';", resultStore.get((String) result.get("resultId")).content());
        assertTrue(String.valueOf(result.get("tip")).contains("取回 PHP WebShell 代码"));

        GenerationRequest request = captured.get();
        assertNotNull(request);
        assertEquals(AgentRuntime.PHP, request.getRuntime());
        assertEquals("webshell", request.getArtifactType());
        assertEquals("portable", request.getOptions().get("outputMode"));
        assertEquals("X-Test", request.getOptions().get("headerName"));
        assertEquals("secret", request.getOptions().get("headerValue"));
        assertEquals(202, request.getOptions().get("respCode"));
        assertEquals("php-test-key", request.getOptions().get("payloadKey"));
        assertEquals("seed-a", request.getOptions().get("seed"));
    }

    @ParameterizedTest
    @CsvSource(value = {"httpchunk,null,仅支持 http", "http,X-Test,必须同时设置"}, nullValues = "null")
    void rejectsInvalidPhpConnectionSettings(String protocol, String headerName, String expectedMessage) {
        ShellGeneratorTools tools = tools(mockDisguiseService(), new ShellResultStore(),
                new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>()))));

        AiToolException error = assertThrows(AiToolException.class,
                () -> tools.generatePhpWebShell(
                        "req", "resp", protocol, "compact", headerName, null, 200, "php-test-key", null));
        assertTrue(error.getMessage().contains(expectedMessage));
    }

    @Test
    void keepsCoreServerSideAndOnlyReturnsFinalResultById() throws Exception {
        DisguiseService disguiseService = mock(DisguiseService.class);
        when(disguiseService.getDisguiseById("req-core")).thenReturn(requestDisguise("req-core"));
        when(disguiseService.getDisguiseById("resp-core")).thenReturn(responseDisguise("resp-core"));
        ShellResultStore resultStore = new ShellResultStore();
        CoreArtifactStore coreStore = new CoreArtifactStore();
        WebShellWrapperTemplateStore templateStore = new WebShellWrapperTemplateStore();
        ShellGeneratorTools tools = new ShellGeneratorTools(
                disguiseService,
                mock(DelegatingChatModel.class),
                resultStore,
                new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>()))),
                coreStore,
                templateStore);

        Map<String, Object> core = tools.createJavaCoreArtifact(
                "req-core", "resp-core", "http", "org.demo.GeneratedCore",
                "8", "javax", "ai-test-key", 405L);

        assertEquals(true, core.get("success"));
        assertNotNull(core.get("coreArtifactId"));
        assertEquals(64, String.valueOf(core.get("coreSha256")).length());
        assertFalse(core.containsKey("bytecode"));
        assertFalse(core.containsKey("base64"));
        assertFalse(core.containsKey("payload"));

        Map<String, Object> contract = tools.getWebShellWrapperContract("JSP", "http");
        String templateId = templateStore.put(
                String.valueOf(contract.get("baselineTemplate")), "JSP", "http");
        Map<String, Object> assembled = tools.assembleWebShellWrapper(
                String.valueOf(core.get("coreArtifactId")), templateId,
                false, null, 200);

        assertEquals(true, assembled.get("success"));
        assertNotNull(assembled.get("resultId"));
        String content = resultStore.get(String.valueOf(assembled.get("resultId"))).content();
        assertTrue(content.contains("org.demo.GeneratedCore"));
        assertFalse(content.contains("{{"));
    }

    @Test
    void retriesInvalidAiWrapperAndStoresOnlyValidatedTemplate() throws Exception {
        DisguiseService disguiseService = mock(DisguiseService.class);
        when(disguiseService.getDisguiseById("req-core")).thenReturn(requestDisguise("req-core"));
        when(disguiseService.getDisguiseById("resp-core")).thenReturn(responseDisguise("resp-core"));
        DelegatingChatModel chatModel = mock(DelegatingChatModel.class);
        WebShellWrapperContract contract = WebShellWrapperContract.create("JSP", "http");
        String invalid = contract.getBaselineTemplate()
                .replace(WebShellWrapperContract.LOAD_CORE, "");
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                response(invalid), response(contract.getBaselineTemplate()));
        CoreArtifactStore coreStore = new CoreArtifactStore();
        WebShellWrapperTemplateStore templateStore = new WebShellWrapperTemplateStore();
        ShellGeneratorTools tools = new ShellGeneratorTools(
                disguiseService, chatModel, new ShellResultStore(),
                new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>()))),
                coreStore, templateStore);
        Map<String, Object> core = tools.createJavaCoreArtifact(
                "req-core", "resp-core", "http", "org.demo.RetryCore",
                "8", "javax", "ai-test-key", 406L);

        Map<String, Object> result = tools.designWebShellWrapper(
                String.valueOf(core.get("coreArtifactId")), "JSP", "保持简洁");

        assertEquals(true, result.get("success"));
        WebShellWrapperTemplateStore.TemplateEntry stored = templateStore.get(
                String.valueOf(result.get("wrapperTemplateId")));
        assertNotNull(stored);
        assertEquals(contract.getBaselineTemplate(), stored.getTemplate());
    }

    private static ShellGeneratorTools tools(DisguiseService disguiseService,
                                              ShellResultStore resultStore,
                                              ScriptGeneratorService generators) {
        return new ShellGeneratorTools(disguiseService, mock(DelegatingChatModel.class), resultStore,
                generators, new CoreArtifactStore(), new WebShellWrapperTemplateStore());
    }

    private static DisguiseService mockDisguiseService() {
        DisguiseService service = mock(DisguiseService.class);
        when(service.getDisguiseById("req")).thenReturn(disguise("req"));
        when(service.getDisguiseById("resp")).thenReturn(disguise("resp"));
        return service;
    }

    private static Disguise disguise(String id) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        return disguise;
    }

    private static Disguise requestDisguise(String id) {
        Disguise disguise = disguise(id);
        disguise.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static Disguise responseDisguise(String id) {
        Disguise disguise = disguise(id);
        disguise.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static ScriptGeneratorProvider phpProvider(AtomicReference<GenerationRequest> captured) {
        return new ScriptGeneratorProvider() {
            @Override
            public AgentRuntime getRuntime() {
                return AgentRuntime.PHP;
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "runtime", "php",
                        "artifactTypes", List.of("webshell"),
                        "outputModes", List.of("compact", "packed", "portable"));
            }

            @Override
            public GeneratedArtifact generate(GenerationRequest request) {
                captured.set(request);
                return new GeneratedArtifact("<?php echo 'fixture';", "php",
                        "application/x-httpd-php",
                        Map.of("runtime", "php", "outputMode", request.getOptions().get("outputMode")),
                        List.of());
            }
        };
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
