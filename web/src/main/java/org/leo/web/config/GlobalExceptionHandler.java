package org.leo.web.config;

import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;

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
        if (e.getHttpStatus().is5xxServerError()) {
            logger.error("API业务异常: {}", e.getMessage(), e);
        } else {
            logger.warn("API业务异常: {}", e.getMessage());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
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

    /**
     * 处理所有未被具体 handler 覆盖的异常，返回 500 错误。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HashMap<String, Object>> handleException(Exception e) {
        logger.error("未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器内部错误：" + e.getMessage()));
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
