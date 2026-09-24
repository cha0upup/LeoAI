package org.leo.web.util;

import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 将 AI 领域事件适配为 SSE，统一发送、心跳、断线容错、队列 drain 和最终 flush。
 */
@Component
public final class AiSseTransport {

    private static final Logger logger = LoggerFactory.getLogger(AiSseTransport.class);
    private final AiSseEventPump eventPump;
    private final AiSseEmitterWriter writer;

    public AiSseTransport(AiSseEventPump eventPump,
                          AiSseEmitterWriter writer) {
        this.eventPump = eventPump;
        this.writer = writer;
    }

    public Session open(String source,
                        AiEventStreamRuntime runtime,
                        SseEmitter emitter,
                        List<AiSseEvent> eventLog) {
        runtime.getAiSseEventQueue().clear();
        Session session = new Session(source, runtime, emitter, eventLog);
        session.handle = eventPump.start(
                source,
                runtime.getAiSseEventQueue(),
                eventLog,
                runtime::getRunStatus,
                session::sendExisting,
                session::sendHeartbeat);
        return session;
    }

    public final class Session {
        private final String source;
        private final AiEventStreamRuntime runtime;
        private final SseEmitter emitter;
        private final List<AiSseEvent> eventLog;
        private AiSseEventPump.Handle handle;

        private Session(String source,
                        AiEventStreamRuntime runtime,
                        SseEmitter emitter,
                        List<AiSseEvent> eventLog) {
            this.source = source;
            this.runtime = runtime;
            this.emitter = emitter;
            this.eventLog = eventLog;
        }

        public void emit(String name, Object data) throws Exception {
            AiSseEvent event = runtime.recordSseEvent(
                    name, data, extractSubagentInvocationId(data));
            sendExisting(event);
        }

        public void emitSafely(String name, Object data) {
            try {
                emit(name, data);
            } catch (Exception ignored) {
                // 客户端断线不能改变后台 Turn 的执行结果。
            }
        }

        public void emitStatus(String status) {
            emitSafely("status", status);
        }

        public void stopAndFlush() {
            eventPump.stop(handle);
            handle = null;
            AiSseEvent event;
            boolean sendAvailable = true;
            while ((event = runtime.getAiSseEventQueue().poll()) != null) {
                eventLog.add(event);
                if (!sendAvailable) continue;
                try {
                    sendExisting(event);
                } catch (Exception error) {
                    sendAvailable = false;
                    logger.debug("{} SSE 队列 flush 停止: {}", source, error.getMessage());
                }
            }
        }

        private void sendExisting(AiSseEvent event) throws Exception {
            writer.sendEvent(emitter, event);
        }

        private void sendHeartbeat(Map<String, Object> payload) throws Exception {
            writer.sendHeartbeat(emitter, payload);
        }
    }

    static String extractSubagentInvocationId(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("subagentInvocationId");
            if (id != null) {
                String text = String.valueOf(id);
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }
}
