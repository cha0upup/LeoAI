package org.leo.web.dto.agentnode.command;

/** Null secrets retain their saved values; an explicit empty passphrase clears it. */
public record SshProfileSaveRequest(String sessionId, String profileId, Long version, String name,
                                    String host, Integer port, String username, String authType,
                                    String password, String privateKey, String passphrase) {
    public SshProfileSaveRequest normalized() {
        SshConnectRequest.validateCredentialSizes(password, privateKey, passphrase);
        String id = profileId == null || profileId.isBlank() ? null : SshProfileRequest.profileId(profileId);
        if (id != null) SshProfileRequest.requireVersion(version);
        else if (version != null) throw org.leo.web.exception.ApiException.badRequest("新建 SSH 配置不接受版本号");
        return new SshProfileSaveRequest(SshConnectRequest.id(sessionId), id, version,
                SshConnectRequest.normalizeName(name), SshConnectRequest.normalizeHost(host),
                SshConnectRequest.normalizePort(port), SshConnectRequest.normalizeUsername(username),
                SshConnectRequest.normalizeAuthType(authType), password, privateKey, passphrase);
    }
    @Override public String toString() { return "SshProfileSaveRequest[credentials redacted]"; }
}
