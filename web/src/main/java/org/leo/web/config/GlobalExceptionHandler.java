package org.leo.web.config;

import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.infrastructure.persistence.session.FileStoreException;
import org.leo.core.util.ApiResponse;
import org.leo.service.sql.DatabaseConnectionProfileException;
import org.leo.service.sql.SqlExecutionException;
import org.leo.service.ssh.SshConnectionException;
import org.leo.web.application.UseCaseException;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理器。
 * 捕获所有控制器未处理的异常，统一返回 {@link ApiResponse} 格式的 JSON 响应，
 * 避免框架默认的 HTML 错误页或裸异常堆栈暴露给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 API 业务异常。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HashMap<String, Object>> handleApiException(ApiException e) {
        return businessError(e, e.getHttpStatus(), e.getDetails());
    }

    @ExceptionHandler(UseCaseException.class)
    public ResponseEntity<HashMap<String, Object>> handleUseCaseException(UseCaseException error) {
        HttpStatus status = switch (error.getKind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OPERATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return businessError(error, status, Map.of());
    }

    @ExceptionHandler(SshConnectionException.class)
    public ResponseEntity<HashMap<String, Object>> handleSshException(SshConnectionException error) {
        HttpStatus status = switch (error.getKind()) {
            case VALIDATION, UNSUPPORTED_CAPABILITY -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case CAPACITY -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PERSISTENCE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return businessError(error, status, Map.of());
    }

    @ExceptionHandler(DatabaseConnectionProfileException.class)
    public ResponseEntity<HashMap<String, Object>> handleDatabaseProfileException(DatabaseConnectionProfileException error) {
        HttpStatus status = switch (error.getKind()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case PERSISTENCE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return businessError(error, status, Map.of());
    }

    @ExceptionHandler(SqlExecutionException.class)
    public ResponseEntity<HashMap<String, Object>> handleSqlExecutionException(SqlExecutionException error) {
        HttpStatus status = switch (error.getStatusCode()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return businessError(error, status, error.details());
    }

    private ResponseEntity<HashMap<String, Object>> businessError(
            Exception error, HttpStatus status, Map<String, Object> details) {
        if (status.is5xxServerError() && status != HttpStatus.SERVICE_UNAVAILABLE) {
            logger.error("API业务异常: {}", error.getMessage(), error);
        } else {
            logger.warn("API业务异常: {}", error.getMessage());
        }
        HashMap<String, Object> body = ApiResponse.error(status.value(), error.getMessage());
        details.forEach(body::putIfAbsent);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 处理参数非法异常（如缺少必填字段）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<HashMap<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("请求参数错误: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<HashMap<String, Object>> handleInvalidJson(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest("请求 JSON 格式不正确"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<HashMap<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(415, "请使用 application/json 提交请求"));
    }

    @ExceptionHandler(FileStoreException.class)
    public ResponseEntity<HashMap<String, Object>> handleFileStore(FileStoreException error) {
        String traceId = UUID.randomUUID().toString();
        logger.error("存储读取异常 traceId={}, reason={}, path={}", traceId, error.getReason(), error.getPath(), error);
        HashMap<String, Object> body = ApiResponse.error(error.getMessage());
        body.put("traceId", traceId);
        body.put("storageError", error.getReason().name());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Missing static files are normal 404s, including stale hashed chunks requested by old tabs. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<HashMap<String, Object>> handleNoResourceFound(NoResourceFoundException e,
                                                                          HttpServletResponse response) {
        logger.debug("静态资源不存在: {}", e.getResourcePath());
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.notFound("静态资源不存在"));
    }

    /**
     * 处理所有未被具体 handler 覆盖的异常，返回 500 错误。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HashMap<String, Object>> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString();
        logger.error("未处理异常 traceId={}", traceId, e);
        HashMap<String, Object> body = ApiResponse.error("服务器内部错误，请稍后重试");
        body.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        logger.warn("客户端连接已断开: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<Void> handleHttpMessageNotWritable(HttpMessageNotWritableException e) {
        logger.warn("响应写出失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
