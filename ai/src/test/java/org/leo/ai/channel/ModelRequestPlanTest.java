package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ModelRequestPlanTest {
    private static final ProviderCapabilities CAPS = new ProviderCapabilities(true, "recognized", "system",
            1_050_000, 128_000, true, true, true, true, true, false, true);

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "glm-5.3", "mimo-v2.6-pro", "qwen3.8-max", "kimi-k2.6"})
    void inheritedThinkingOmitsExplicitSwitchesAndStillPreservesReturnedThinking(String model) {
        AiModelConfig config = config(model);
        ModelRequestPlan plan = ModelRequestPlan.resolve(config, CAPS, null, false, false);
        assertTrue(plan.doReasoning());
        assertTrue(plan.returnThinking());
        assertFalse(plan.preview().requestParameters().containsKey("thinking"));
        assertFalse(plan.preview().requestParameters().containsKey("enable_thinking"));
        assertFalse(plan.preview().requestParameters().containsKey("reasoning_effort"));
        assertEquals("default", decision(plan, "thinkingEnabled").status());
    }

    @ParameterizedTest
    @CsvSource({"deepseek-flash,thinking", "mimo-v2.6-pro,thinking", "qwen3.8-max,enable_thinking", "kimi-k2.6,thinking"})
    void explicitThinkingIsConvertedToTheVendorWireField(String model, String field) {
        AiModelConfig config = config(model);
        config.setThinkingEnabled(0);
        ModelRequestPlan plan = ModelRequestPlan.resolve(config, CAPS, null, false, false);
        assertFalse(plan.doReasoning());
        assertEquals(field.equals("thinking") ? Map.of("type", "disabled") : false,
                plan.preview().requestParameters().get(field));
        assertEquals("sent", decision(plan, "thinkingEnabled").status());
    }

    @Test
    void samplingRulesDependOnTheModeAndKeepIgnoredValuesVisibleWithoutSendingThem() {
        AiModelConfig config = config("mimo-v2.6-pro");
        config.setTemperature(1.2);
        ModelRequestPlan thinking = ModelRequestPlan.resolve(config, CAPS, null, false, false);
        assertFalse(thinking.preview().contract().temperatureConfigurable());
        assertEquals(1.5, thinking.preview().contract().parameters().get("temperature").maximum());
        assertEquals("omitted", decision(thinking, "temperature").status());
        assertEquals(1.2, decision(thinking, "temperature").configuredValue());
        assertFalse(thinking.preview().requestParameters().containsKey("temperature"));
        config.setThinkingEnabled(0);
        ModelRequestPlan instant = ModelRequestPlan.resolve(config, CAPS, null, false, false);
        assertTrue(instant.preview().contract().temperatureConfigurable());
        assertEquals(1.2, instant.preview().requestParameters().get("temperature"));
        config.setTemperature(null);
        assertFalse(ModelRequestPlan.resolve(config, CAPS, null, false, false).preview().requestParameters().containsKey("temperature"));
    }

    @Test
    void validationReturnsFieldErrorsAndInferenceRejectsTheSameInvalidDraft() {
        AiModelConfig config = config("mimo-v2.6-pro");
        config.setThinkingEnabled(2);
        config.setReasoningEffort("high");
        config.setTemperature(Double.NaN);
        config.setMaxOutputTokens(0);
        config.setContextWindowTokens(-1);
        ModelRequestPlan.Preview preview = ModelRequestPlan.preview(config, CAPS);
        assertFalse(preview.valid());
        assertEquals(Set.of("thinkingEnabled", "reasoningEffort", "temperature", "maxOutputTokens", "contextWindowTokens"),
                preview.errors().stream().map(ModelRequestPlan.FieldError::field).collect(Collectors.toSet()));
        ModelRequestPlan.ValidationException failure = assertThrows(ModelRequestPlan.ValidationException.class,
                () -> ModelRequestPlan.resolve(config, CAPS, null, false, false));
        assertEquals(preview.errors(), failure.errors());
        assertTrue(preview.requestParameters().isEmpty());
    }

    @Test
    void fixedModesAndProtocolRequirementsAreValidatedBeforeRuntimeConstruction() {
        AiModelConfig kimi = config("kimi-k3.1-pro");
        kimi.setThinkingEnabled(0);
        assertEquals("thinkingEnabled", ModelRequestPlan.preview(kimi, CAPS).errors().get(0).field());
        AiModelConfig openai = config("gpt-6.1-sol");
        assertEquals("protocol", ModelRequestPlan.preview(openai, CAPS).errors().get(0).field());
        openai.setProtocol("responses");
        assertTrue(ModelRequestPlan.preview(openai, CAPS).valid());
    }

    @Test
    void tokenLimitsAreClampedAndContextRemainsALocalBudget() {
        AiModelConfig config = config("gpt-6.1-sol");
        config.setProtocol("responses");
        config.setMaxOutputTokens(200_000);
        config.setContextWindowTokens(1_000_000);
        ModelRequestPlan plan = ModelRequestPlan.resolve(config, CAPS, null, false, false);
        assertEquals(922_000, plan.contextWindowTokens());
        assertEquals(128_000, plan.preview().requestParameters().get("max_output_tokens"));
        assertEquals("clamped", decision(plan, "maxOutputTokens").status());
        assertEquals("local", decision(plan, "contextWindowTokens").status());
        assertFalse(plan.preview().requestParameters().containsKey("contextWindowTokens"));
    }

    @Test
    void publicPreviewExcludesSecretsAndGatewayDefaultsDoNotForceAReasoningMode() {
        AiModelConfig config = config("deepseek/deepseek-flash");
        config.setProviderKey("openrouter");
        config.setApiKey("private-model-token");
        config.setHeadersJson("{\"Authorization\":\"Bearer private-header-token\"}");
        ModelRequestPlan.Preview preview = ModelRequestPlan.preview(config, CAPS);
        assertTrue(preview.valid());
        assertEquals("gateway", preview.contract().source());
        assertFalse(preview.requestParameters().containsKey("reasoning"));
        assertFalse(JSON.toJSONString(preview).contains("private-"));
        assertFalse(JSON.toJSONString(preview).contains("Authorization"));
    }

    @Test
    void unlistedFutureNamesDoNotInheritHardCodedFamilyLimits() {
        AiModelConfig config = config("gpt-6-future-model");
        assertEquals("conservative", ModelRequestPlan.preview(config, CAPS).contract().source());
        assertEquals(CAPS.contextWindowTokens(), ModelRequestOptions.inputWindow("custom", config.getModel(), CAPS.contextWindowTokens()));
    }

    @Test
    void inheritedModeDoesNotSendNoneWhenAnUnknownResponsesModelHasNoReasoningCapability() {
        AiModelConfig config = config("custom-model");
        config.setProtocol("responses");
        ProviderCapabilities noReasoning = new ProviderCapabilities(true, "manual", "manual",
                32000, 4096, true, false, true, true, true, false, false);
        ModelRequestPlan inherited = ModelRequestPlan.resolve(config, noReasoning, null, false, false);
        assertFalse(inherited.preview().requestParameters().containsKey("reasoning"));
        assertEquals("default", decision(inherited, "thinkingEnabled").status());
        config.setThinkingEnabled(0);
        assertEquals(Map.of("effort", "none"),
                ModelRequestPlan.resolve(config, noReasoning, null, false, false).preview().requestParameters().get("reasoning"));
    }

    @Test
    void glmSamplingLimitsAndPrecisionComeFromItsContract() {
        AiModelConfig config = config("glm-5.3");
        config.setTemperature(1.1);
        assertEquals("temperature", ModelRequestPlan.preview(config, CAPS).errors().get(0).field());
        config.setTemperature(0.123);
        assertFalse(ModelRequestPlan.preview(config, CAPS).valid());
        config.setTemperature(0.12);
        assertTrue(ModelRequestPlan.preview(config, CAPS).valid());
    }

    private static AiModelConfig config(String model) {
        AiModelConfig config = new AiModelConfig();
        config.setProviderKey("custom");
        config.setModel(model);
        config.setProtocol("chat_completions");
        config.setReasoningEffort("auto");
        return config;
    }

    private static ModelRequestPlan.ParameterValue decision(ModelRequestPlan plan, String field) {
        return plan.preview().parameters().stream().filter(value -> value.field().equals(field)).findFirst().orElseThrow();
    }
}
