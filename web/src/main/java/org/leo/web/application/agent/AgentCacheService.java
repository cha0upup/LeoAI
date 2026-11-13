package org.leo.web.application.agent;

import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository;
import org.leo.web.dto.agentnode.CacheCheckResponse;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** HostId-scoped agent cache operations. */
@Service
public class AgentCacheService {

    private final AgentHostCacheRepository repository;

    public AgentCacheService(AgentHostCacheRepository repository) {
        this.repository = repository;
    }

    public CacheCheckResponse status(String userId, String agentId) {
        var cache = repository.readCache(userId, agentId);
        boolean available = !cache.hostIds().isEmpty();
        return new CacheCheckResponse(available,
                available ? cache.saveTime() : null);
    }

    public List<String> hostIds(String userId, String agentId) {
        return repository.readCache(userId, agentId).hostIds();
    }

    public String requireSelectedHostId(String userId, String agentId, String selectedHostId) {
        List<String> hostIds = hostIds(userId, agentId);
        if (hostIds.isEmpty()) throw ApiException.badRequest("无本地缓存，无法进入缓存模式");
        String selected = selectedHostId == null || selectedHostId.isBlank() ? null : selectedHostId.trim();
        if (selected == null && hostIds.size() == 1) return hostIds.get(0);
        if (selected == null) throw ApiException.badRequest("请选择要进入的缓存后端主机");
        if (!hostIds.contains(selected)) throw ApiException.badRequest("指定的缓存后端主机不存在");
        return selected;
    }

    public Map<String, Object> loadBasicInfo(String userId, String agentId, String hostId) {
        return repository.loadBasicInfo(userId, agentId, hostId);
    }

    public void saveBasicInfo(String sessionId, String hostId, Map<String, Object> basicInfo) {
        repository.saveBasicInfo(sessionId, hostId, basicInfo);
    }
}
