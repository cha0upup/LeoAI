package org.leo.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatMemoryProviderFactoryTest {

    @Test
    void keepsSmallModelWithinItsRealWindow() {
        assertEquals(12_768,
                AiChatMemoryProviderFactory.effectiveContextWindowTokens(32_768, 180_000, 0));
    }

    @Test
    void treatsConfiguredContextAsMaximum() {
        assertEquals(100_000,
                AiChatMemoryProviderFactory.effectiveContextWindowTokens(200_000, 100_000, 0));
    }

    @Test
    void reservesActualToolSchemaBudget() {
        assertEquals(10_768,
                AiChatMemoryProviderFactory.effectiveContextWindowTokens(
                        32_768, 180_000, 10_000));
    }

    @Test
    void preservesMinimumWorkingBudgetForTinyWindows() {
        assertEquals(1_024,
                AiChatMemoryProviderFactory.effectiveContextWindowTokens(8_192, 180_000, 0));
    }
}
