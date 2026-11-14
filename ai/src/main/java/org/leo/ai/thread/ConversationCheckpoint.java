package org.leo.ai.thread;

/** Persisted conversation summary and the stable message boundary it covers. */
public record ConversationCheckpoint(String summary, long boundarySequence, String boundaryHash, int version) {}
