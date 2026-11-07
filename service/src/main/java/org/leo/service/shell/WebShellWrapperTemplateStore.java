package org.leo.service.shell;

import org.leo.core.util.BoundedTtlCache;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 已通过契约校验的 AI Wrapper 模板临时存储。 */
@Component
public class WebShellWrapperTemplateStore {

    public static final class TemplateEntry {
        private final String template;
        private final String artifactType;
        private final String protocol;

        private TemplateEntry(String template, String artifactType, String protocol) {
            this.template = template;
            this.artifactType = artifactType;
            this.protocol = protocol;
        }

        public String getTemplate() {
            return template;
        }

        public String getArtifactType() {
            return artifactType;
        }

        public String getProtocol() {
            return protocol;
        }
    }

    private final BoundedTtlCache<TemplateEntry> store = new BoundedTtlCache<>(
            256, 30, TimeUnit.MINUTES, BoundedTtlCache.EvictionOrder.INSERTION);

    public String put(String template, String artifactType, String protocol) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Wrapper 模板不能为空");
        }
        store.evictExpired();
        String id = UUID.randomUUID().toString();
        store.put(id, new TemplateEntry(template, artifactType, protocol));
        return id;
    }

    public TemplateEntry get(String id) {
        if (id == null || id.isBlank()) return null;
        store.evictExpired();
        return store.get(id.trim());
    }
}
