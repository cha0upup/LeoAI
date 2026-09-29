package org.leo.core.config;

import org.junit.jupiter.api.Test;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.disguise.DisguiseProtocol;
import org.leo.core.entity.Disguise;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisguiseConfigTest {

    @Test
    void customBase64UsesConfiguredAlphabetAndRoundTripsOpaqueBytes() throws Exception {
        Disguise disguise = customBase64Disguise();

        assertEquals("mkXD", new String(
                disguise.encodeTraffic("abc".getBytes(StandardCharsets.US_ASCII)),
                StandardCharsets.US_ASCII));

        for (int length : Arrays.asList(0, 1, 2, 3, 16, 257)) {
            byte[] input = new byte[length];
            for (int i = 0; i < input.length; i++) {
                input[i] = (byte) (i * 31 + 7);
            }
            assertArrayEquals(input, disguise.decodeTraffic(disguise.encodeTraffic(input)));
        }
    }

    @Test
    void missingRuntimeDeclarationDoesNotImplyJavaSupport() throws Exception {
        Disguise disguise = customBase64Disguise();
        disguise.setSupportedRuntimes(null);
        assertFalse(disguise.supportsRuntime("java"));
        disguise.setSupportedRuntimes(Set.of());
        assertFalse(disguise.supportsRuntime("java"));
    }

    @Test
    void declaredPhpRuntimeNeedsItsOwnImplementation() throws Exception {
        Disguise disguise = customBase64Disguise();
        disguise.setSupportedRuntimes(Set.of("php"));
        assertThrows(IllegalArgumentException.class, () -> DisguiseProtocol.requireCurrent(disguise));
    }

    private Disguise customBase64Disguise() throws Exception {
        return JavaBuiltinDisguiseCatalog.createPresets().get(1);
    }
}
