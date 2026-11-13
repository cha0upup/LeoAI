package org.leo.web.adapter.ai;

import org.leo.core.entity.AiSseEvent;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一 AI SSE 的发送格式与连接收尾。 */
@Component
public class AiSseEmitterWriter {

    public void sendEvent(SseEmitter emitter, AiSseEvent event) throws IOException {
        if (emitter == null) return;
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event.name());
        if (event.data() instanceof String text) {
            builder.data(text, MediaType.TEXT_PLAIN);
        } else {
            builder.data(event.data() != null ? event.data() : "");
        }
        if (event.seq() > 0) {
            builder.id(eventId(event));
        }
        send(emitter, builder);
    }

    /**
     * SSE id 同时携带稳定路由元数据；首段始终是纯数字游标，客户端仍只用它补拉。
     */
    private String eventId(AiSseEvent event) {
        return event.seq()
                + "|" + field(event.turnId())
                + "|" + field(event.itemId())
                + "|" + field(event.runId())
                + "|" + field(event.subagentInvocationId());
    }

    private String field(String value) {
        return value != null ? value : "";
    }

    public void sendHeartbeat(SseEmitter emitter, Map<String, Object> payload)
            throws IOException {
        if (emitter == null) return;
        send(emitter, SseEmitter.event()
                .name("heartbeat")
                .data(payload != null ? payload : new LinkedHashMap<>()));
    }

    public void sendStatus(SseEmitter emitter, String status) throws IOException {
        if (emitter == null) return;
        send(emitter, SseEmitter.event()
                .name("status")
                .data(status != null ? status : "idle", MediaType.TEXT_PLAIN));
    }

    public static void safeSendError(SseEmitter emitter, String message) {
        if (emitter == null) return;
        try {
            send(emitter, SseEmitter.event().name("error").data(message != null ? message : "未知错误"));
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
            // The connection may already be complete.
        }
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event)
            throws IOException {
        if (emitter == null) return;
        synchronized (emitter) {
            emitter.send(event);
        }
    }
}
