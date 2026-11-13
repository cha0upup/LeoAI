package org.leo.web.exception;

import org.leo.core.util.ApiResponse;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 入口及适配器的响应异常。
 *
 * <p>保留 HTTP 状态及接口专用明细；应用用例使用传输无关的 UseCaseException。
 * 统一响应格式由 {@code GlobalExceptionHandler} 生成。
 */
public class ApiException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;
    private final Map<String, Object> details;

    private ApiException(int code, HttpStatus httpStatus, String message) {
        this(code, httpStatus, message, Map.of());
    }

    private ApiException(int code,
                         HttpStatus httpStatus,
                         String message,
                         Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ApiResponse.CODE_BAD_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ApiResponse.CODE_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ApiResponse.CODE_FORBIDDEN, HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ApiResponse.CODE_NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, HttpStatus.CONFLICT, message);
    }
    public static ApiException conflict(String message, Map<String, Object> details) {
        return new ApiException(409, HttpStatus.CONFLICT, message, details);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(503, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(ApiResponse.CODE_TOO_MANY_REQUESTS,
                HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static ApiException serverError(String message) {
        return new ApiException(ApiResponse.CODE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
