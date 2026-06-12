package org.leo.ai.audit;

import org.leo.core.entity.AiChatAuditEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * AI 对话审计日志内存存储。
 *
 * <p>采用固定容量的环形缓冲：超过 {@link #MAX_ENTRIES} 时自动丢弃最旧的记录。
 * 线程安全，适合高并发写入场景。
 */
@Component
public class AiAuditLogStore {

    private static final int MAX_ENTRIES = 2000;

    private final Deque<AiChatAuditEntry> deque = new ConcurrentLinkedDeque<>();

    /** 写入一条审计记录（超容时丢弃最旧记录）。 */
    public void append(AiChatAuditEntry entry) {
        if (entry == null) return;
        deque.addFirst(entry);
        while (deque.size() > MAX_ENTRIES) {
            deque.pollLast();
        }
    }

    /**
     * 查询最近 N 条记录（最新在前）。
     *
     * @param limit 最多返回条数，0 或负数表示返回全部
     */
    public List<AiChatAuditEntry> recent(int limit) {
        List<AiChatAuditEntry> result = new ArrayList<>(deque);
        if (limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    /** 清空所有记录。 */
    public void clear() {
        deque.clear();
    }

    public int size() {
        return deque.size();
    }
}
