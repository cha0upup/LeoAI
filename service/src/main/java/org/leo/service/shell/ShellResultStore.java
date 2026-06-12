package org.leo.service.shell;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存马 / WebShell 生成结果的临时缓存。
 *
 * <p>解决 Platform AI 工具调用时 LLM 会截断超长字符串的问题：
 * 工具将完整代码存入此缓存并返回 {@code resultId}，
 * 前端凭 {@code resultId} 通过 REST 端点直接取回原始内容，绕过 LLM 上下文传递。
 *
 * <p>TTL 默认 30 分钟，采用懒清理策略（每次 get 时清理过期条目），
 * 无需额外定时任务依赖。
 */
@Component
public class ShellResultStore {

    /** 条目存活时长（毫秒）：30 分钟 */
    private static final long TTL_MS = 30 * 60 * 1000L;

    private static final class Entry {
        final String content;
        final Map<String, Object> meta;
        final long expiresAt;

        Entry(String content, Map<String, Object> meta) {
            this.content   = content;
            this.meta      = meta;
            this.expiresAt = System.currentTimeMillis() + TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 存储生成结果，返回唯一 ID。
     *
     * @param content 完整代码字符串（JSP / 内存马 payload）
     * @param meta    摘要信息（packerType、serverType 等，供 AI 回复用）
     * @return 唯一 resultId
     */
    public String put(String content, Map<String, Object> meta) {
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(content, meta));
        return id;
    }

    /**
     * 取回完整代码内容。
     *
     * @param id resultId
     * @return 代码字符串，条目不存在或已过期时返回 {@code null}
     */
    public String getContent(String id) {
        evictExpired();
        Entry entry = store.get(id);
        if (entry == null || entry.isExpired()) {
            store.remove(id);
            return null;
        }
        return entry.content;
    }

    /**
     * 取回摘要元数据（不含完整代码）。
     *
     * @param id resultId
     * @return 元数据 Map，条目不存在或已过期时返回 {@code null}
     */
    public Map<String, Object> getMeta(String id) {
        evictExpired();
        Entry entry = store.get(id);
        if (entry == null || entry.isExpired()) {
            store.remove(id);
            return null;
        }
        return entry.meta;
    }

    /** 懒清理：移除所有已过期条目。 */
    private void evictExpired() {
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
    }
}
