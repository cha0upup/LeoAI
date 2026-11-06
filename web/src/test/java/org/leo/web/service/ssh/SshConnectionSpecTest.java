package org.leo.web.service.ssh;

import org.junit.jupiter.api.Test;
import org.leo.web.exception.ApiException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SshConnectionSpecTest {
    private static final String PIN = "SHA256:" + "A".repeat(43);

    @Test
    void normalizesOnConstructionAndRetainsOnlyActiveCredentials() {
        var password = new SshConnectionSpec("process", " host ", null, " user ", null,
                "  exact password 中文\n", "unused-key", "unused-passphrase", PIN);
        assertEquals("host", password.host());
        assertEquals(22, password.port());
        assertEquals("user", password.username());
        assertEquals("password", password.authType());
        assertEquals("  exact password 中文\n", password.password());
        assertNull(password.privateKey());
        assertNull(password.passphrase());

        var key = new SshConnectionSpec("process", "::1", 65535, "user", "privateKey",
                "unused-password", " key\n", " passphrase ", PIN);
        assertEquals("::1", key.host());
        assertEquals(65535, key.port());
        assertNull(key.password());
        assertEquals(" key\n", key.privateKey());
        assertEquals(" passphrase ", key.passphrase());
    }

    @Test
    void rejectsInvalidIdentifiersAndEndpointFields() {
        for (String id : Arrays.asList(null, "", "bad:id", " parent ", "a".repeat(129))) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec(id, "host", 22,
                    "user", "password", "secret", null, null, PIN));
        }
        for (String host : Arrays.asList(null, "", "bad/path", "bad\\path", "bad host", "bad\u0000host", "a".repeat(256))) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec("process", host, 22,
                    "user", "password", "secret", null, null, PIN));
        }
        for (int port : List.of(0, 65536)) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", port,
                    "user", "password", "secret", null, null, PIN));
        }
        for (String username : Arrays.asList(null, " ", "bad\nuser", "a".repeat(129))) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22,
                    username, "password", "secret", null, null, PIN));
        }
        assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22,
                "user", "unknown", "secret", null, null, PIN));
    }

    @Test
    void requiresEffectiveCredentialsAndAConfirmedPin() {
        for (String password : Arrays.asList(null, "")) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22,
                    "user", "password", password, null, null, PIN));
        }
        for (String key : Arrays.asList(null, "", " \t")) {
            assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22,
                    "user", "privateKey", null, key, null, PIN));
        }
        for (String pin : Arrays.asList(null, "", PIN + "=", "SHA256:short", "SHA1:" + "A".repeat(43))) {
            var error = assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22,
                    "user", "password", "secret", null, null, pin));
            assertEquals(400, error.getCode());
            assertEquals("请先核验 SSH 主机指纹", error.getMessage());
        }
        assertEquals("   ", new SshConnectionSpec("process", "host", 22,
                "user", "password", "   ", null, null, PIN).password());
    }

    @Test
    void boundsAllCredentialFieldsBeforeDiscardingInactiveOnes() {
        assertDoesNotThrow(() -> new SshConnectionSpec("process", "host", 22, "user", "password",
                "p".repeat(8192), "k".repeat(131072), "s".repeat(8192), PIN));
        assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22, "user", "privateKey",
                "p".repeat(8193), "key", null, PIN));
        assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22, "user", "password",
                "password", "k".repeat(131073), null, PIN));
        assertThrows(ApiException.class, () -> new SshConnectionSpec("process", "host", 22, "user", "password",
                "password", null, "s".repeat(8193), PIN));
    }

    @Test
    void redactsCredentialsFromDiagnosticOutput() {
        var spec = new SshConnectionSpec("process", "host", 22, "user", "privateKey",
                null, "fixture-private-key", "fixture-passphrase", PIN);
        assertEquals("SshConnectionSpec[credentials redacted]", spec.toString());
    }
}
