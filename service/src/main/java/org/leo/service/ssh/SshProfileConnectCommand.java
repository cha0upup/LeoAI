package org.leo.service.ssh;

/** Select a saved profile and an explicitly confirmed or previously trusted host key. */
public record SshProfileConnectCommand(String processId, String profileId, Long version, String fingerprint) {
    public SshProfileConnectCommand {
        processId = SshInputValidation.requireSessionId(processId);
        profileId = SshInputValidation.normalizeProfileId(profileId);
        version = SshInputValidation.requireVersion(version);
        fingerprint = SshInputValidation.optionalFingerprint(fingerprint);
    }
}
