package org.leo.core.util.request;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentClassNameStrategyTest {

    @Test
    void randomNamesStayUniqueAcrossConcurrentCallsAndRepeatWithTheSameSeed() {
        var names = IntStream.range(0, 2048).parallel()
                .mapToObj(ignored -> ClassNameGenerator.generateServletStyleClassName()).toList();
        assertEquals(names.size(), Set.copyOf(names).size());

        String seeded;
        try (var scope = GenerationRandom.withSeed(42L)) {
            seeded = ClassNameGenerator.generateServletStyleClassName();
        }
        try (var scope = GenerationRandom.withSeed(42L)) {
            assertEquals(seeded, ClassNameGenerator.generateServletStyleClassName());
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "INNER_CLASS,true,.+\\$[A-Za-z]+",
            "LAMBDA_SHAPED,true,.+\\$\\$Lambda\\$[0-9]+",
            "PROXY_SHAPED,true,.+\\.proxy\\.\\$Proxy[0-9]+",
            "APPLICATION,true,baseline",
            "null,true,baseline",
            "INNER_CLASS,false,baseline"
    }, nullValues = "null")
    void generatesStableProfileNamesPerComponent(
            ComponentClassNameStrategy.Mode mode, boolean enabled, String pattern) {
        ComponentClassNameStrategy strategy = new ComponentClassNameStrategy();
        strategy.setMode(mode);
        strategy.setEnabled(enabled);

        String first = strategy.resolve("host-a|endpoint", "BasicInfoComponent");
        assertEquals(first, strategy.resolve("host-a|endpoint", "BasicInfoComponent"));
        assertNotEquals(first, strategy.resolve("host-a|endpoint", "FileComponent"));
        if ("baseline".equals(pattern)) {
            assertEquals(ClassNameGenerator.generateComponentClassName("host-a|endpoint", "BasicInfoComponent"), first);
        } else {
            assertTrue(first.matches(pattern));
        }
    }

    @Test
    void validatesClassFileBinaryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentClassNameStrategy.validate("Foo$$Lambda$1/0x0001"));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentClassNameStrategy.validate("java.lang.String$Holder"));
    }
}
