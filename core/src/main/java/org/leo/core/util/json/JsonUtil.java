package org.leo.core.util.json;

import com.alibaba.fastjson.JSON;

/**
 * JSON工具类（基于 FastJSON）
 * 提供统一的JSON序列化和反序列化功能，确保使用UTF-8编码
 */
public class JsonUtil {

    /**
     * 将对象序列化为JSON字符串
     * FastJSON 默认使用 UTF-8 编码
     *
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
    public static Object fromJsonString(String json,Class cls){

        return JSON.parseObject(json,cls);

    }
}