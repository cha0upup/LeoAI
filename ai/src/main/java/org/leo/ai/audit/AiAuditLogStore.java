package org.leo.ai.audit;

import org.leo.core.entity.AiChatAuditEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * AI 对话审计日志内存存储。
 *
 * <p>保留最近的 {@link #MAX_ENTRIES} 条记录。新增、读取和清空共享同一把锁。
 */
@Component
public class AiAuditLogStore {

    private static final int MAX_ENTRIES = 2000;

    private final Deque<AiChatAuditEntry> entries = new ArrayDeque<>();

    /** 写入一条审计记录（超容时丢弃最旧记录）。 */
    public synchronized void append(AiChatAuditEntry entry) {
        if (entry == null) return;
        if (entries.size() == MAX_ENTRIES) {
            entries.removeLast();
        }
        entries.addFirst(entry);
    }

    /**
     * 一次读取总数和最近 N 条记录（最新在前）。
     *
     * @param limit 最多返回条数，0 或负数表示返回全部
     */
    public synchronized Snapshot snapshot(int limit) {
        int total = entries.size();
        int count = limit > 0 ? Math.min(total, limit) : total;
        List<AiChatAuditEntry.Snapshot> result = new ArrayList<>(count);
        for (AiChatAuditEntry entry : entries) {
            if (result.size() == count) break;
            result.add(entry.snapshot());
        }
        return new Snapshot(total, List.copyOf(result));
    }

    /** 清空所有记录。 */
    public synchronized void clear() {
        entries.clear();
    }

    public record Snapshot(int total, List<AiChatAuditEntry.Snapshot> logs) {
    }
}
