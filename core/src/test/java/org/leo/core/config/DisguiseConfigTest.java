package org.leo.core.config;

import org.junit.jupiter.api.Test;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.entity.Disguise;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private Disguise customBase64Disguise() throws Exception {
        return JavaBuiltinDisguiseCatalog.createPresets().get(1);
    }
}
