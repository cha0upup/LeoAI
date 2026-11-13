package org.leo.service.shell;

import org.leo.core.util.BoundedTtlCache;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * AI 生成结果的临时缓存；完整内容通过 resultId 交付，避免经 LLM 上下文截断。
 * 最多保留 256 条、最长 30 分钟，满容量时按写入顺序淘汰。
 */
@Component
public class ShellResultStore {

    private final BoundedTtlCache<Result> store = new BoundedTtlCache<>(
            256, 30, TimeUnit.MINUTES, BoundedTtlCache.EvictionOrder.INSERTION);

    public String put(String content, Map<String, Object> meta) {
        Result result = new Result(content, meta);
        store.evictExpired();
        String id = UUID.randomUUID().toString();
        store.put(id, result);
        return id;
    }

    /** 一次读取内容及元数据；不存在或已过期时返回 null。 */
    public Result get(String id) {
        if (id == null || id.isBlank()) return null;
        store.evictExpired();
        return store.get(id.trim());
    }

    public record Result(String content, Map<String, Object> meta) {
        public Result {
            if (content == null) throw new IllegalArgumentException("生成结果不能为空");
            meta = meta == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
        }
    }
}
