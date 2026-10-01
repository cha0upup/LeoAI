package org.leo.web.service;

import org.leo.core.repository.session.AgentHostCacheRepository;
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
        boolean available = repository.hasAgentCache(userId, agentId);
        return new CacheCheckResponse(available,
                available ? repository.getAgentCacheSaveTime(userId, agentId) : null);
    }

    public List<String> hostIds(String userId, String agentId) {
        return repository.listCachedHostIds(userId, agentId);
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
