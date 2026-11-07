package org.leo.service.ssh;

/** Application input, independent of HTTP sessions. Null secrets retain their saved values. */
public record SshProfileSaveCommand(String profileId, Long version, String name,
                                     String host, Integer port, String username, String authType,
                                     String password, String privateKey, String passphrase) {
    public SshProfileSaveCommand {
        SshInputValidation.validateCredentialSizes(password, privateKey, passphrase);
        profileId = profileId == null || profileId.isBlank() ? null : SshInputValidation.normalizeProfileId(profileId);
        if (profileId != null) SshInputValidation.requireVersion(version);
        else if (version != null) throw new SshConnectionException(
                SshConnectionException.Kind.VALIDATION, "新建 SSH 配置不接受版本号");
        name = SshInputValidation.normalizeName(name);
        host = SshInputValidation.normalizeHost(host);
        port = SshInputValidation.normalizePort(port);
        username = SshInputValidation.normalizeUsername(username);
        authType = SshInputValidation.normalizeAuthType(authType);
    }

    @Override public String toString() { return "SshProfileSaveCommand[credentials redacted]"; }
}
