package org.leo.web.exception;

import org.leo.core.util.ApiResponse;
import org.springframework.http.HttpStatus;

/**
 * API 层业务异常。
 *
 * <p>Controller 只负责表达失败语义，统一响应格式由 {@code GlobalExceptionHandler}
 * 生成，避免各接口散落 try/catch 和手写错误 Map。
 */
public class ApiException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    private ApiException(int code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
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

    public static ApiException serverError(String message) {
        return new ApiException(ApiResponse.CODE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
