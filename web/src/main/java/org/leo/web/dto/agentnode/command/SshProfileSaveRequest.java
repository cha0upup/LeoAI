package org.leo.web.dto.agentnode.command;

import org.leo.web.exception.ApiException;
import org.leo.web.util.SshInputValidation;

/** Null secrets retain their saved values; an explicit empty passphrase clears it. */
public record SshProfileSaveRequest(String sessionId, String profileId, Long version, String name,
                                    String host, Integer port, String username, String authType,
                                    String password, String privateKey, String passphrase) {
    public SshProfileSaveRequest normalized() {
        SshInputValidation.validateCredentialSizes(password, privateKey, passphrase);
        String id = profileId == null || profileId.isBlank() ? null : SshInputValidation.normalizeProfileId(profileId);
        if (id != null) {
            SshInputValidation.requireVersion(version);
        } else if (version != null) {
            throw ApiException.badRequest("新建 SSH 配置不接受版本号");
        }
        return new SshProfileSaveRequest(SshInputValidation.requireSessionId(sessionId), id, version,
                SshInputValidation.normalizeName(name), SshInputValidation.normalizeHost(host),
                SshInputValidation.normalizePort(port), SshInputValidation.normalizeUsername(username),
                SshInputValidation.normalizeAuthType(authType), password, privateKey, passphrase);
    }

    @Override
    public String toString() {
        return "SshProfileSaveRequest[credentials redacted]";
    }
}
