package org.leo.ai.channel;

import org.leo.core.entity.AiModelConfig;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * AI 模型配置服务（ccswitch 风格的单表 CRUD）。
 *
 * <p>每条记录都是一组完整的 "name + base_url + api_key + model + 可选高级项"。
 * 同一时刻只允许一条 {@code is_active=1}。激活时会触发 {@link DynamicModelProvider#refresh()}
 * 热切换底层模型。
 */
@Service
public class AiModelConfigService {

    private final AiModelConfigMapper mapper;
    private volatile DynamicModelProvider dynamicModelProvider;

    public AiModelConfigService(AiModelConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 由 DynamicModelProvider 在初始化后回调注入，避免循环依赖。 */
    public void setDynamicModelProvider(DynamicModelProvider provider) {
        this.dynamicModelProvider = provider;
    }

    public List<AiModelConfig> listAll() {
        return mapper.listAll();
    }

    public AiModelConfig findById(Integer id) {
        return id == null ? null : mapper.findById(id);
    }

    public AiModelConfig getActive() {
        return mapper.findActive();
    }

    public AiModelConfig create(AiModelConfig row) {
        validateRequired(row);
        normalize(row);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (row.getIsActive() == null) {
            row.setIsActive(0);
        }
        // 第一条自动激活
        if (mapper.countAll() == 0) {
            row.setIsActive(1);
        } else if (Integer.valueOf(1).equals(row.getIsActive())) {
            mapper.clearActive();
        }
        mapper.insert(row);
        if (Integer.valueOf(1).equals(row.getIsActive())) {
            notifyModelRefresh();
        }
        return mapper.findById(row.getId());
    }

    public AiModelConfig update(Integer id, AiModelConfig patch) {
        AiModelConfig existing = findById(id);
        if (existing == null) return null;
        boolean wasActive = Integer.valueOf(1).equals(existing.getIsActive());
        if (!isBlank(patch.getName())) existing.setName(patch.getName().trim());
        if (!isBlank(patch.getBaseUrl())) existing.setBaseUrl(patch.getBaseUrl().trim());
        if (patch.getApiKey() != null && !patch.getApiKey().isEmpty()) {
            existing.setApiKey(patch.getApiKey());
        }
        if (!isBlank(patch.getModel())) existing.setModel(patch.getModel().trim());
        if (!isBlank(patch.getCompletionsPath())) {
            existing.setCompletionsPath(patch.getCompletionsPath().trim());
        }
        if (patch.getMaxOutputTokens() != null) {
            existing.setMaxOutputTokens(patch.getMaxOutputTokens() > 0 ? patch.getMaxOutputTokens() : null);
        }
        if (patch.getThinkingEnabled() != null) {
            existing.setThinkingEnabled(normalizeTriStateFlag(patch.getThinkingEnabled()));
        }
        if (patch.getContextWindowTokens() != null) {
            existing.setContextWindowTokens(patch.getContextWindowTokens() > 0
                    ? patch.getContextWindowTokens() : null);
        }
        if (patch.getRemark() != null) existing.setRemark(patch.getRemark());
        if (patch.getIsActive() != null) {
            if (Integer.valueOf(1).equals(patch.getIsActive())) {
                mapper.clearActive();
                existing.setIsActive(1);
            } else {
                existing.setIsActive(0);
            }
        }
        existing.setUpdateTime(nowSqlite());
        normalize(existing);
        mapper.update(existing);
        boolean isActiveNow = Integer.valueOf(1).equals(existing.getIsActive());
        if (wasActive || isActiveNow) {
            notifyModelRefresh();
        }
        return mapper.findById(id);
    }

    public boolean deleteById(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return false;
        mapper.deleteById(id);
        return true;
    }

    public AiModelConfig activate(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return null;
        mapper.clearActive();
        mapper.setActiveById(id, nowSqlite());
        AiModelConfig activated = mapper.findById(id);
        notifyModelRefresh();
        return activated;
    }

    /** 解析"要使用的模型"。requested 可为空，空则取激活记录。 */
    public AiModelConfig resolve(Integer requestedId) {
        if (requestedId != null) {
            AiModelConfig found = mapper.findById(requestedId);
            if (found != null) return found;
        }
        return getActive();
    }

    public AiModelConfig requireActive() {
        AiModelConfig active = getActive();
        if (active == null) {
            throw new IllegalStateException("未配置激活的 AI 模型，请先在设置中添加并激活一条");
        }
        return active;
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private void validateRequired(AiModelConfig row) {
        if (row == null) throw new IllegalArgumentException("配置不能为空");
        if (isBlank(row.getName())) throw new IllegalArgumentException("name 不能为空");
        if (isBlank(row.getApiKey())) throw new IllegalArgumentException("apiKey 不能为空");
        if (isBlank(row.getBaseUrl())) throw new IllegalArgumentException("baseUrl 不能为空");
        if (isBlank(row.getModel())) throw new IllegalArgumentException("model 不能为空");
    }

    private void normalize(AiModelConfig row) {
        row.setName(row.getName().trim());
        row.setBaseUrl(row.getBaseUrl().trim());
        row.setModel(row.getModel().trim());
        if (isBlank(row.getCompletionsPath())) {
            row.setCompletionsPath("/v1/chat/completions");
        } else {
            row.setCompletionsPath(row.getCompletionsPath().trim());
        }
        if (row.getMaxOutputTokens() != null && row.getMaxOutputTokens() <= 0) {
            row.setMaxOutputTokens(null);
        }
        if (row.getContextWindowTokens() != null && row.getContextWindowTokens() <= 0) {
            row.setContextWindowTokens(null);
        }
        row.setThinkingEnabled(normalizeTriStateFlag(row.getThinkingEnabled()));
    }

    private static Integer normalizeTriStateFlag(Integer v) {
        if (v == null) return null;
        return v > 0 ? 1 : 0;
    }

    private void notifyModelRefresh() {
        DynamicModelProvider provider = this.dynamicModelProvider;
        if (provider != null) provider.refresh();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nowSqlite() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
