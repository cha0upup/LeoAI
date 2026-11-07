package org.leo.service.ssh;

/** Validated, server-resolved transport parameters; never used as a public request or response. */
public record SshConnectionSpec(String processId, String host, Integer port,
                                String username, String authType, String password, String privateKey,
                                String passphrase, String fingerprint) {
    public SshConnectionSpec {
        processId = SshInputValidation.requireSessionId(processId);
        host = SshInputValidation.normalizeHost(host);
        port = SshInputValidation.normalizePort(port);
        username = SshInputValidation.normalizeUsername(username);
        authType = SshInputValidation.normalizeAuthType(authType);
        SshInputValidation.validateCredentials(authType, password, privateKey, passphrase);
        fingerprint = SshInputValidation.requireFingerprint(fingerprint);
        if ("privateKey".equals(authType)) {
            password = null;
        } else {
            privateKey = null;
            passphrase = null;
        }
    }

    @Override
    public String toString() {
        return "SshConnectionSpec[credentials redacted]";
    }
}
