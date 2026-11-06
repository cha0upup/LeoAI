package org.leo.service.ssh;

import java.util.List;
import java.util.Map;

/**
 * Application port for Agent-scoped SSH management. Implementations recheck the authenticated
 * user's current session access and return metadata only, never decrypted credentials.
 * Keeps AI independent of the HTTP DTOs and the SSH terminal implementation.
 */
public interface SshConnectionOperations {
    List<Map<String, Object>> list(String userId, String sessionId);

    Map<String, Object> get(String userId, String sessionId, String profileId);

    Map<String, Object> create(String userId, String sessionId, Map<String, Object> config);

    Map<String, Object> update(String userId, String sessionId, String profileId,
                               Long version, Map<String, Object> patch);

    Map<String, Object> delete(String userId, String sessionId, String profileId, Long version);

    /** A non-null fingerprint must have been explicitly confirmed by the caller's user. */
    Map<String, Object> test(String userId, String sessionId, String profileId,
                             Long version, String confirmedFingerprint);
}
