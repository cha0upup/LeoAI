package org.leo.core.util.javassist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavassistDisguiseFactoryTest {

    @Test
    void definesAndRunsTrafficAdapterOnJava17WithoutModuleOpens() throws Exception {
        String encode = "public byte[] encodeTraffic(byte[] payload) { "
                + "return java.util.Base64.getEncoder().encode(payload); }";
        String decode = "public byte[] decodeTraffic(byte[] data) { "
                + "return java.util.Base64.getDecoder().decode(data); }";

        assertTrue(JavassistDisguiseFactory.testTrafficDisguise(encode, decode));
    }
}
