package org.leo.ai.agent;

import org.leo.core.util.BoundedTtlCache;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 工具超大结果的短期归档。
 *
 * <p>归档按 memoryId 隔离，模型只能通过同一会话的分页工具读取，避免把
 * 一个会话的命令输出暴露给另一个会话。最多保留 256 条，满容量时按写入顺序淘汰。
 */
@Component
public class AiToolResultArchive {

    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;
    private final BoundedTtlCache<Entry> entries;

    public AiToolResultArchive() {
        this(System::currentTimeMillis);
    }

    AiToolResultArchive(LongSupplier clock) {
        entries = new BoundedTtlCache<>(256, DEFAULT_TTL_MS, TimeUnit.MILLISECONDS,
                BoundedTtlCache.EvictionOrder.INSERTION, clock);
    }

    public String put(String memoryId, String toolName, String content,
                      Map<String, Object> metadata, long ttlMs) {
        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(
                memoryId,
                toolName,
                content != null ? content : "",
                metadata != null ? Map.copyOf(metadata) : Map.of());
        entries.evictExpired();
        entries.put(id, entry, Math.max(1_000L, ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS),
                TimeUnit.MILLISECONDS);
        return id;
    }

    public ArchivePage page(String memoryId, String archiveId, int offset, int limit) {
        entries.evictExpired();
        Entry entry = entries.get(archiveId);
        if (entry == null || !Objects.equals(entry.memoryId, memoryId)) {
            return null;
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(8_000, Math.max(1, limit));
        if (safeOffset > entry.content.length()) safeOffset = entry.content.length();
        int end = safeOffset + Math.min(safeLimit, entry.content.length() - safeOffset);
        return new ArchivePage(
                archiveId,
                entry.toolName,
                entry.content.substring(safeOffset, end),
                safeOffset,
                end,
                entry.content.length(),
                end < entry.content.length(),
                entry.metadata);
    }

    public int size() {
        entries.evictExpired();
        return entries.size();
    }

    private record Entry(String memoryId, String toolName, String content,
                         Map<String, Object> metadata) {
    }

    public record ArchivePage(String archiveId, String toolName, String content,
                              int offset, int endOffset, int totalChars,
                              boolean hasMore, Map<String, Object> metadata) {
    }
}
