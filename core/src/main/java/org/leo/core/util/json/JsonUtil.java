package org.leo.core.util.json;

import com.alibaba.fastjson.JSON;

/**
 * 基于 FastJSON 的 JSON 文本序列化与类型化解析。
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * 将对象序列化为JSON字符串
     * @param obj 要序列化的对象
     * @return JSON字符串
     * @throws RuntimeException 如果序列化失败
     */
    public static String toJsonString(Object obj) {
        try {
            return JSON.toJSONString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    public static <T> T fromJsonString(String json, Class<T> type) {
        return JSON.parseObject(json, type);
    }
}
