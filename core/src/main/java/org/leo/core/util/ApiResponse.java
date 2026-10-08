package org.leo.core.util;

import java.util.HashMap;

/**
 * 统一API响应格式
 * 所有接口统一使用此格式返回数据
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class ApiResponse {
    
    // 状态码常量
    public static final int CODE_SUCCESS = 200;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_CONFLICT = 409;
    public static final int CODE_TOO_MANY_REQUESTS = 429;
    public static final int CODE_ERROR = 500;
    
    // 响应字段常量
    private static final String FIELD_CODE = "code";
    private static final String FIELD_MSG = "msg";
    private static final String FIELD_DATA = "data";
    
    /**
     * 创建成功响应（无数据）
     */
    public static HashMap<String, Object> success() {
        return success(null);
    }
    
    /**
     * 创建成功响应（带数据）
     */
    public static HashMap<String, Object> success(Object data) {
        return success(null, data);
    }
    
    /**
     * 创建成功响应（带消息和数据）
     */
    public static HashMap<String, Object> success(String message, Object data) {
        return createResponse(CODE_SUCCESS, message != null ? message : "success", data);
    }
    
    /**
     * 创建错误响应
     */
    public static HashMap<String, Object> error(int code, String message) {
        return createResponse(code, message != null ? message : "error", null);
    }
    
    /**
     * 创建错误响应（默认500）
     */
    public static HashMap<String, Object> error(String message) {
        return error(CODE_ERROR, message);
    }
    
    /**
     * 创建400错误响应
     */
    public static HashMap<String, Object> badRequest(String message) {
        return error(CODE_BAD_REQUEST, message);
    }
    
    /**
     * 创建401错误响应
     */
    public static HashMap<String, Object> unauthorized(String message) {
        return error(CODE_UNAUTHORIZED, message);
    }
    
    /**
     * 创建403错误响应
     */
    public static HashMap<String, Object> forbidden(String message) {
        return error(CODE_FORBIDDEN, message);
    }
    
    /**
     * 创建404错误响应
     */
    public static HashMap<String, Object> notFound(String message) {
        return error(CODE_NOT_FOUND, message);
    }

    /**
     * 创建409冲突错误响应。
     */
    public static HashMap<String, Object> conflict(String message) {
        return error(CODE_CONFLICT, message);
    }

    public static HashMap<String, Object> tooManyRequests(String message) {
        return error(CODE_TOO_MANY_REQUESTS, message);
    }

    private static HashMap<String, Object> createResponse(int code, String message, Object data) {
        HashMap<String, Object> result = new HashMap<>();
        result.put(FIELD_CODE, code);
        result.put(FIELD_MSG, message);
        if (data != null) result.put(FIELD_DATA, data);
        return result;
    }
}
