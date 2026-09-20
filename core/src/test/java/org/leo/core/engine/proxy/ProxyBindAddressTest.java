package org.leo.core.engine.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyBindAddressTest {
    @Test
    void acceptsLocalWildcardAndSpecificInterfaceAddresses() throws Exception {
        assertEquals("0.0.0.0", ProxyBindAddress.normalize(null));
        assertEquals("0.0.0.0", ProxyBindAddress.normalize("  "));
        assertEquals("0.0.0.0", ProxyBindAddress.normalize(" 0.0.0.0 "));
        assertEquals("192.168.1.10", ProxyBindAddress.normalize("192.168.1.10"));
        assertEquals(InetAddress.getByName("::1").getHostAddress(), ProxyBindAddress.normalize("[::1]"));
        assertEquals(InetAddress.getByName("::").getHostAddress(), ProxyBindAddress.normalize("::"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"256.1.1.1", "127.1", "example.invalid", "127.0.0.1:1080", "http://127.0.0.1",
            "[::1]:1080", "1::2::3", "127.0.0.1\u0000", "\u0000", "::1\n", "[127.0.0.1]", "::1%", "::1%a/b"})
    void rejectsInvalidAddressesWithoutFallingBackToWildcard(String address) {
        assertThrows(IllegalArgumentException.class, () -> ProxyBindAddress.normalize(address));
    }
}
