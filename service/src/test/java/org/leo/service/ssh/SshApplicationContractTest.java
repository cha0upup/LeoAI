package org.leo.service.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SshApplicationContractTest {
    private static final String ID = "cfe5054d-84ea-4a81-b9c5-f2f8795f99dc";
    private static final String PIN = "SHA256:" + "A".repeat(43);

    @Test void saveCommandsNormalizeFieldsWithoutNeedingAnHttpSession() {
        var command = new SshProfileSaveCommand(null, null, " build ", " ssh.internal ", null,
                " ops ", null, " exact password \n", null, null);
        assertEquals("build", command.name());
        assertEquals("ssh.internal", command.host());
        assertEquals(22, command.port());
        assertEquals("ops", command.username());
        assertEquals("password", command.authType());
        assertEquals(" exact password \n", command.password());
        assertFalse(command.toString().contains("exact password"));
    }

    @Test void updatesAllowRetainedCredentialsButRequireVersionedIdentity() {
        var retained = new SshProfileSaveCommand(ID.toUpperCase(), 1L, "", "host", 22,
                "ops", "privateKey", null, null, "");
        assertEquals(ID, retained.profileId());
        assertNull(retained.privateKey());
        assertEquals("", retained.passphrase());
        assertThrows(SshConnectionException.class, () -> new SshProfileSaveCommand(ID, null, "", "host", 22,
                "ops", "password", null, null, null));
        assertThrows(SshConnectionException.class, () -> new SshProfileSaveCommand(null, 1L, "", "host", 22,
                "ops", "password", "secret", null, null));
        assertThrows(SshConnectionException.class, () -> new SshProfileSaveCommand(null, null, "", "host", 22,
                "ops", "password", "p".repeat(8193), null, null));
    }

    @Test void connectCommandsKeepMissingPinsDistinctFromConfirmedPins() {
        assertNull(new SshProfileConnectCommand("process", ID, 1L, null).fingerprint());
        assertEquals(PIN, new SshProfileConnectCommand("process", ID, 1L, PIN).fingerprint());
        assertThrows(SshConnectionException.class, () -> new SshProfileConnectCommand("process", ID, 1L, ""));
        assertThrows(SshConnectionException.class, () -> new SshProfileConnectCommand("process", ID, 0L, PIN));
    }
}
