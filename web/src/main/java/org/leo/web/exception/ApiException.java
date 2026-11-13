package org.leo.web.exception;

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

    private final HttpStatus httpStatus;
    private final Map<String, Object> details;

    private ApiException(HttpStatus httpStatus, String message) {
        this(httpStatus, message, Map.of());
    }

    private ApiException(HttpStatus httpStatus,
                         String message,
                         Map<String, Object> details) {
        super(message);
        this.httpStatus = httpStatus;
        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }

    public int getCode() {
        return httpStatus.value();
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException conflict(String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, message, details);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static ApiException serverError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
