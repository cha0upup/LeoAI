package org.leo.core.util.request;

import org.junit.jupiter.api.Test;

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

    @Test
    void generatesStableProfileNamesPerComponent() {
        ComponentClassNameStrategy strategy = new ComponentClassNameStrategy();
        strategy.setMode(ComponentClassNameStrategy.Mode.INNER_CLASS);

        String first = strategy.resolve("host-a|endpoint", "BasicInfoComponent");
        assertEquals(first, strategy.resolve("host-a|endpoint", "BasicInfoComponent"));
        assertNotEquals(first, strategy.resolve("host-a|endpoint", "FileComponent"));
        assertTrue(first.contains("$"));
    }

    @Test
    void generatesLambdaShapedNames() {
        ComponentClassNameStrategy strategy = new ComponentClassNameStrategy();
        strategy.setMode(ComponentClassNameStrategy.Mode.LAMBDA_SHAPED);

        String basic = strategy.resolve("session", "BasicInfoComponent");
        String file = strategy.resolve("session", "FileComponent");
        assertTrue(basic.contains("$$Lambda$"));
        assertNotEquals(basic, file);
    }

    @Test
    void generatesProxyShapedNames() {
        ComponentClassNameStrategy strategy = new ComponentClassNameStrategy();
        strategy.setMode(ComponentClassNameStrategy.Mode.PROXY_SHAPED);

        assertTrue(strategy.resolve("session", "BasicInfoComponent")
                .matches(".+\\.proxy\\.\\$Proxy[0-9]+"));
    }

    @Test
    void validatesClassFileBinaryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentClassNameStrategy.validate("Foo$$Lambda$1/0x0001"));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentClassNameStrategy.validate("java.lang.String$Holder"));
    }
}
