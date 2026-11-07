package org.leo.ai.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatMemoryProviderFactoryTest {

    @ParameterizedTest(name = "model={0}, configured={1}, schema={2} -> budget={3}")
    @CsvSource({
            "32768,180000,0,12768",
            "200000,100000,0,100000",
            "32768,180000,10000,10768",
            "8192,180000,0,1024"
    })
    void respectsModelAndConfiguredLimitsWhileReservingToolAndMinimumBudgets(
            int modelWindow, int configuredMax, int schemaTokens, int expected) {
        assertEquals(expected, AiChatMemoryProviderFactory.effectiveContextWindowTokens(
                modelWindow, configuredMax, schemaTokens));
    }
}
