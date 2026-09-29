package org.leo.service.config;

import org.leo.dao.mapper.SystemConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    public SystemConfigService(SystemConfigMapper systemConfigMapper) {
        this.systemConfigMapper = systemConfigMapper;
    }

    public String getString(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        String value = systemConfigMapper.findValueByKey(key.trim());
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /** Reads a bounded integer, preserving the default if configuration cannot be read or parsed. */
    public int getInt(String key, int defaultValue, int min, int max) {
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException("默认值必须在配置范围内");
        }
        try {
            int value = Integer.parseInt(getString(key, String.valueOf(defaultValue)));
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    public void setString(String key, String value, String description) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        systemConfigMapper.upsert(
                key.trim(),
                value == null ? "" : value.trim(),
                "string",
                description
        );
    }
}
