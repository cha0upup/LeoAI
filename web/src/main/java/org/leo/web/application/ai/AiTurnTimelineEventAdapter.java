package org.leo.web.application.ai;

import org.leo.ai.runtime.AiTurnEvent;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 将执行引擎的传输层无关事件适配到现有 timeline/SSE 事件协议。
 */
final class AiTurnTimelineEventAdapter {

    private final AiTimelineRecorder recorder;
    private final BiConsumer<String, Object> sink;
    private final Runnable thinkingStarted;
    private final Runnable toolCompleted;
    private final boolean recordToolEvents;

    AiTurnTimelineEventAdapter(AiTimelineRecorder recorder,
                               BiConsumer<String, Object> sink,
                               Runnable thinkingStarted,
                               Runnable toolCompleted,
                               boolean recordToolEvents) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.thinkingStarted = thinkingStarted != null ? thinkingStarted : () -> {};
        this.toolCompleted = toolCompleted != null ? toolCompleted : () -> {};
        this.recordToolEvents = recordToolEvents;
    }

    void accept(AiTurnEvent event) {
        switch (event.type()) {
            case THINKING_DELTA -> {
                String text = (String) event.data();
                if (text == null || text.isEmpty()) return;
                if (!recorder.hasPendingThinking()) {
                    thinkingStarted.run();
                }
                recorder.appendThinking(text);
            }
            case TEXT_DELTA -> {
                recorder.appendVisibleDelta((String) event.data());
                recorder.flushDelta();
            }
            case TOOL_CALL_DELTA -> {
                recorder.onBoundary();
                sink.accept("tool_delta", event.data());
            }
            case TOOL_STARTED -> {
                recorder.onBoundary();
                sink.accept("node", event.data());
                recordExternal("node", event.data());
            }
            case TOOL_COMPLETED -> {
                recorder.flushThinking();
                sink.accept("patch", event.data());
                recordExternal("patch", event.data());
                toolCompleted.run();
            }
        }
    }

    private void recordExternal(String name, Object data) {
        if (recordToolEvents) {
            recorder.recordExternal(name, data);
        }
    }
}
