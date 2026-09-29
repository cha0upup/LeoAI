package org.leo.jmg.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Disguise;
import org.leo.jmg.ShellGeneratorConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.leo.jmg.TrafficTestFixtures.requestDisguise;
import static org.leo.jmg.TrafficTestFixtures.responseDisguise;

class LeoCoreValidationTest {
    private static final String CLASS_NAME = "org.example.ValidationCore";
    private static final String PAYLOAD_KEY = "validation-test-key";
    private final CoreGenerationNames names = CoreGenerationNames.from(
            ShellGeneratorConfig.builder(requestDisguise(), responseDisguise())
                    .payloadKey(PAYLOAD_KEY)
                    .coreClassName(CLASS_NAME)
                    .shellClassName("org.example.ValidationShell")
                    .injectorClassName("org.example.ValidationInjector")
                    .header("X-Test", "validation")
                    .serverType("Tomcat")
                    .shellType("FilterInjector")
                    .packerType("DefaultBase64")
                    .build());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingClassName(String className) {
        LeoCore core = new LeoCore(requestDisguise(), responseDisguise(), PAYLOAD_KEY);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> core.dump(className, names));
        assertEquals("classname不能为空", error.getMessage());
    }

    @Test
    void rejectsMissingGenerationNames() {
        LeoCore core = new LeoCore(requestDisguise(), responseDisguise(), PAYLOAD_KEY);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> core.dump(CLASS_NAME, (CoreGenerationNames) null));
        assertEquals("CoreGenerationNames不能为空", error.getMessage());
    }

    @Test
    void rejectsMissingRequestDisguise() {
        assertInvalid(new LeoCore(null, responseDisguise(), PAYLOAD_KEY),
                "Java Core 必须使用 traffic-only Disguise");
    }

    @Test
    void rejectsMissingResponseDisguise() {
        assertInvalid(new LeoCore(requestDisguise(), null, PAYLOAD_KEY),
                "Java Core 必须使用 traffic-only Disguise");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingRequestDecoder(String source) {
        Disguise request = requestDisguise();
        request.setTrafficDecodeBody(source);
        assertInvalid(new LeoCore(request, responseDisguise(), PAYLOAD_KEY),
                "Java Core 必须使用 traffic-only Disguise");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingResponseEncoder(String source) {
        Disguise response = responseDisguise();
        response.setTrafficEncodeBody(source);
        assertInvalid(new LeoCore(requestDisguise(), response, PAYLOAD_KEY),
                "Java Core 必须使用 traffic-only Disguise");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingPayloadKey(String key) {
        assertInvalid(new LeoCore(requestDisguise(), responseDisguise(), key), "payloadKey不能为空");
    }

    @Test
    void validatesConfigurationBeforeCompilingSource() {
        Disguise request = requestDisguise();
        request.setTrafficDecodeBody("invalid java source");
        assertInvalid(new LeoCore(request, responseDisguise(), ""), "payloadKey不能为空");
    }

    private void assertInvalid(LeoCore core, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> core.dump(CLASS_NAME, names));
        assertEquals(message, error.getMessage());
    }
}
