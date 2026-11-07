package org.leo.service.shell;

import org.leo.core.util.BoundedTtlCache;
import org.leo.jmg.generation.CoreArtifact;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 服务端 CoreArtifact 临时存储；Core 字节码不会返回给 LLM。 */
@Component
public class CoreArtifactStore {

    private final BoundedTtlCache<CoreArtifact> store = new BoundedTtlCache<>(
            256, 30, TimeUnit.MINUTES, BoundedTtlCache.EvictionOrder.INSERTION);

    public String put(CoreArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("CoreArtifact 不能为空");
        store.evictExpired();
        String id = UUID.randomUUID().toString();
        store.put(id, artifact);
        return id;
    }

    public CoreArtifact get(String id) {
        if (id == null || id.isBlank()) return null;
        store.evictExpired();
        return store.get(id.trim());
    }
}
