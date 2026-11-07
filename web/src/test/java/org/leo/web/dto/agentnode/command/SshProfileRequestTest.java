package org.leo.web.dto.agentnode.command;

import org.junit.jupiter.api.Test;
import org.leo.web.exception.ApiException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SshProfileRequestTest {
    private static final String PROFILE_ID = "f99ac527-c963-4581-9656-6a99fce70004";
    private static final String PIN = "SHA256:" + "A".repeat(43);

    private SshProfileSaveRequest save(String id, Long version, String password, String key, String passphrase) {
        return new SshProfileSaveRequest("parent", id, version, " build ", " host ", null,
                " user ", null, password, key, passphrase);
    }

    @Test
    void sharesCanonicalProfileIdentifiersAcrossRequests() {
        String upper = PROFILE_ID.toUpperCase(Locale.ROOT);
        assertEquals(PROFILE_ID, new SshProfileRequest("parent", upper, null).normalized().profileId());
        assertEquals(PROFILE_ID, save(upper, 1L, null, null, null).toCommand().profileId());
        assertEquals(PROFILE_ID, new SshProfileConnectRequest("parent", "child", upper, 1L, PIN)
                .toCommand().profileId());
        for (String invalid : Arrays.asList(null, "", "1-1-1-1-1", "../profile", PROFILE_ID + " ")) {
            assertThrows(ApiException.class, () -> new SshProfileRequest("parent", invalid, null).normalized());
            assertThrows(ApiException.class, () -> new SshProfileConnectRequest("parent", "child", invalid, 1L, PIN).toCommand());
        }
    }

    @Test
    void enforcesVersionRulesForNewProfilesEditsReadsAndConnections() {
        assertNull(save(" ", null, null, null, null).toCommand().profileId());
        assertThrows(ApiException.class, () -> save(null, 1L, null, null, null).toCommand());
        assertNull(new SshProfileRequest("parent", PROFILE_ID, null).normalized().version());
        assertEquals((long) Integer.MAX_VALUE,
                new SshProfileRequest("parent", PROFILE_ID, (long) Integer.MAX_VALUE).normalized().version());
        for (Long invalid : Arrays.asList(null, 0L, -1L, (long) Integer.MAX_VALUE + 1)) {
            assertThrows(ApiException.class, () -> save(PROFILE_ID, invalid, null, null, null).toCommand());
            assertThrows(ApiException.class, () -> new SshProfileConnectRequest("parent", "child", PROFILE_ID, invalid, PIN).toCommand());
            if (invalid != null) {
                assertThrows(ApiException.class, () -> new SshProfileRequest("parent", PROFILE_ID, invalid).normalized());
            }
        }
    }

    @Test
    void defaultsNewProfileFieldsAndPreservesOmittedOrExplicitSecrets() {
        var omitted = save(null, null, null, null, null).toCommand();
        assertEquals("build", omitted.name());
        assertEquals("host", omitted.host());
        assertEquals(22, omitted.port());
        assertEquals("user", omitted.username());
        assertEquals("password", omitted.authType());
        assertNull(omitted.password());
        assertNull(omitted.privateKey());
        assertNull(omitted.passphrase());

        var suppliedRequest = save(PROFILE_ID, 1L, "  secret\n", " key\n", "");
        var supplied = suppliedRequest.toCommand();
        assertEquals("  secret\n", supplied.password());
        assertEquals(" key\n", supplied.privateKey());
        assertEquals("", supplied.passphrase());
        assertEquals("SshProfileSaveRequest[credentials redacted]", suppliedRequest.toString());
        assertEquals("SshProfileSaveCommand[credentials redacted]", supplied.toString());
    }

    @Test
    void allowsAnOmittedPinButRejectsMalformedBrowserPins() {
        assertNull(new SshProfileConnectRequest("parent", "child", PROFILE_ID, 1L, null).toCommand().fingerprint());
        assertEquals(PIN, new SshProfileConnectRequest("parent", "child", PROFILE_ID, 1L, PIN).toCommand().fingerprint());
        for (String pin : List.of("", PIN + "=", "SHA256:short", "SHA1:" + "A".repeat(43))) {
            var error = assertThrows(ApiException.class,
                    () -> new SshProfileConnectRequest("parent", "child", PROFILE_ID, 1L, pin).toCommand());
            assertEquals(400, error.getCode());
            assertEquals("SSH 主机指纹格式无效", error.getMessage());
        }
    }

    @Test
    void rejectsOversizedSecretsBeforePersistenceEvenWhenNotUsedByTheAuthType() {
        assertDoesNotThrow(() -> save(null, null, "p".repeat(8192), "k".repeat(131072), "s".repeat(8192)).toCommand());
        assertThrows(ApiException.class, () -> save(null, null, "p".repeat(8193), null, null).toCommand());
        assertThrows(ApiException.class, () -> save(null, null, null, "k".repeat(131073), null).toCommand());
        assertThrows(ApiException.class, () -> save(null, null, null, null, "s".repeat(8193)).toCommand());
    }
}
