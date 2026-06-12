package org.leo.web.config;

import org.leo.core.session.PuppetNodeSessionContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PuppetNode 会话 TTL 清理器。
 *
 * <p>定期扫描 {@link PuppetNodeSessionContainer}，将超过 {@value #MAX_IDLE_MS} ms
 * 未活跃的会话从内存中驱逐，防止僵尸会话长期占用内存。
 *
 * <p>活跃时间通过 {@code PuppetNodeSession#touchLastActiveTime()} 更新：
 * 会话创建、AI 重置（新建会话）以及每次 chat 请求均会刷新。
 */
@Component
public class PuppetNodeSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(PuppetNodeSessionCleanup.class);

    /** 最大空闲时长：12 小时。 */
    private static final long MAX_IDLE_MS = 12 * 60 * 60 * 1000L;

    /**
     * 每 30 分钟扫描一次，驱逐超过 12 小时未活跃的会话。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 30 * 60 * 1000L)
    public void evictExpiredSessions() {
        List<String> evicted = PuppetNodeSessionContainer.evictExpired(MAX_IDLE_MS);
        if (!evicted.isEmpty()) {
            log.info("[PuppetNodeSession] 清理过期会话 {} 个：{}", evicted.size(), evicted);
        }
    }
}
