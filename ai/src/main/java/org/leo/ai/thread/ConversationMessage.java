package org.leo.ai.thread;

/** Stable message sequence and resolved model context, shared by persistence and memory consumers. */
public record ConversationMessage(Long sequence, String role, String content) {}
