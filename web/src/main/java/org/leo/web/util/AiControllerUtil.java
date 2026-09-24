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
     * 安全地向 SseEmitter 发送 error 事件并关闭连接。
     * 发送失败或连接已断开时仍尝试结束流。
     */
    public static void safeSendError(SseEmitter emitter, String message) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(message != null ? message : "未知错误"));
        } catch (Exception ignored) {
            // The client may already have disconnected.
        } finally {
            safeComplete(emitter);
        }
    }

    /** 发送结构化错误元数据和终止流的 error 事件。 */
    public static void safeSendError(SseEmitter emitter, AiErrorClassifier.Classification classification) {
        if (emitter == null) return;
        String message = classification != null ? classification.message() : "未知错误";
        try {
            if (classification != null) {
                emitter.send(SseEmitter.event().name("error_meta").data(classification.toMap()));
            }
            emitter.send(SseEmitter.event().name("error")
                    .data(message != null ? message : "未知错误"));
        } catch (Exception ignored) {
            // The client may already have disconnected.
        } finally {
            safeComplete(emitter);
        }
    }

    public static void safeComplete(SseEmitter emitter) {
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // ignore disconnected / already completed emitters
        }
    }
}
