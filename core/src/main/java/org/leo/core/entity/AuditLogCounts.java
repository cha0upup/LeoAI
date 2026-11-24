package org.leo.core.entity;

public record AuditLogCounts(long total, long successCount, long failedCount) {
}
