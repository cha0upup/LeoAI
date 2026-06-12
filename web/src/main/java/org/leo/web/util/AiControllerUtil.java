package org.leo.web.util;

import org.leo.ai.service.AiErrorClassifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 控制器公共工具类。
 *
 * <p>提取 {@code PlatformAiController} 和 {@code PuppetNodeAiController} 中重复的
 * 私有方法，统一维护。
 */
public final class AiControllerUtil {

    private AiControllerUtil() {
    }

    // ── SSE 工具 ────────────────────────────────────────────────────────────────

    /**
     * 安全地向 SseEmitter 发送状态事件。状态发送失败通常表示客户端已经断开，
     * 后台任务生命周期不应该因此被打断。
     */
    public static void safeSendStatus(SseEmitter emitter, String status) {
        try {
            emitter.send(SseEmitter.event().name("status").data(status));
        } catch (Exception ignored) {
            // ignore disconnected clients
        }
    }

    /**
     * 安全地向 SseEmitter 发送 error 事件并关闭连接。
     * 如果发送本身也失败，则直接 completeWithError。
     */
    public static void safeSendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(message != null ? message : "未知错误"));
            safeComplete(emitter);
        } catch (Exception e) {
            safeComplete(emitter);
        }
    }

    /**
     * 发送结构化错误元数据和兼容旧前端的纯文本 error 事件。
     */
    public static void safeSendError(SseEmitter emitter, AiErrorClassifier.Classification classification) {
        String message = classification != null ? classification.message() : "未知错误";
        try {
            if (classification != null) {
                emitter.send(SseEmitter.event().name("error_meta").data(classification.toMap()));
            }
            emitter.send(SseEmitter.event().name("error")
                    .data(message != null ? message : "未知错误"));
            safeComplete(emitter);
        } catch (Exception e) {
            safeComplete(emitter);
        }
    }

    public static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // ignore disconnected / already completed emitters
        }
    }
}
