package org.leo.ai.thread;

/** Persisted Turn, Run and message identifiers, including the lease fence for subsequent writes. */
public record PersistedTurn(String turnId, String runId, String threadId,
                            String userMessageId, String assistantMessageId,
                            long startedAt, String leaseToken) {
}
