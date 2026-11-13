package org.leo.web.application.ai;

import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 累积 LangChain4j 流式回调中的可见 token、思考 token、工具/Plan/子任务等事件，
 * 在 thinking / tool / complete 等边界把缓冲落成 timeline 节点事件。
 *
 * <p>三块缓冲：
 * <ul>
 *   <li>{@code delta}        — 自上次 {@link #flushDelta()} 以来的可见 token，攒批后以
 *                              {@code delta} 事件下发，只服务于前端直播，不入 eventLog</li>
 *   <li>{@code textSegment}  — 自上次 {@link #finalizeTextSegment()} 以来的可见 token，
 *                              在 thinking / tool / complete 边界封装成
 *                              {@code node{kind:"text"}} 事件并写入 eventLog 用于持久化</li>
 *   <li>{@code thinking}     — 思考 token，{@link #flushThinking()} 时产出
 *                              {@code node{kind:"thinking"}} 事件并写入 eventLog</li>
 * </ul>
 *
 * <p>事件通过 {@link Sink} 写入运行时日志，本类只负责累积和边界化。
 * 每个 Turn 使用独立实例，回调与持久化由执行引擎串行处理，无后台读写线程。
 */
final class AiTimelineRecorder {

    /** 事件记录回调；实现方负责 thread/state 端 {@code recordSseEvent} 持久化。 */
    @FunctionalInterface
    interface Sink {
        void emit(String name, Object data);
    }

    private final Sink sink;
    private final StringBuilder delta       = new StringBuilder();
    private final StringBuilder thinking    = new StringBuilder();
    private final StringBuilder textSegment = new StringBuilder();
    private final List<AiSseEvent> eventLog = new ArrayList<>();

    AiTimelineRecorder(Sink sink) {
        this.sink = sink;
    }

    /** 本轮持久化用的 timeline 事件序列。 */
    List<AiSseEvent> eventLog() {
        return eventLog;
    }

    boolean hasPendingThinking() {
        return thinking.length() > 0;
    }

    // ── 累积 ────────────────────────────────────────────────────────────────

    /**
     * 进入文本流。若仍有未 flush 的 thinking，先 {@link #flushThinking()} 保证事件顺序为
     * thinking → text；之后 token 进入直播 delta 和持久化 textSegment 缓冲。
     */
    void appendVisibleDelta(String text) {
        if (text == null || text.isEmpty()) return;
        if (thinking.length() > 0) flushThinking();
        delta.append(text);
        textSegment.append(text);
    }

    /**
     * 进入思考流。若仍有未 finalize 的 textSegment，先 {@link #finalizeTextSegment()}
     * 保证事件顺序为 text → thinking。
     */
    void appendThinking(String text) {
        if (text == null || text.isEmpty()) return;
        if (textSegment.length() > 0) finalizeTextSegment();
        thinking.append(text);
    }

    // ── 边界 flush ──────────────────────────────────────────────────────────

    /** 把累积的 delta 攒批后以 {@code delta} 事件下发；不入 eventLog（仅直播）。 */
    void flushDelta() {
        if (delta.length() == 0) return;
        String payload = delta.toString();
        delta.setLength(0);
        sink.emit("delta", payload);
    }

    /** thinking 缓冲落地为 {@code node{kind:"thinking"}} 事件并写入 eventLog。 */
    void flushThinking() {
        if (thinking.length() == 0) return;
        String content = thinking.toString();
        thinking.setLength(0);
        emitTextNode("thinking", content);
    }

    /**
     * textSegment 缓冲落地为 {@code node{kind:"text"}} 事件并写入 eventLog。
     * 先 {@link #flushDelta()} 让前端直播流闭合，再发节点；空白片段直接丢弃。
     */
    private void finalizeTextSegment() {
        flushDelta();
        if (textSegment.length() == 0) return;
        String content = textSegment.toString();
        textSegment.setLength(0);
        if (content.isBlank()) return;
        emitTextNode("text", content);
    }

    private void emitTextNode(String kind, String content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("content", content);
        entry.put("timestamp", System.currentTimeMillis());
        sink.emit("node", entry);
        eventLog.add(new AiSseEvent("node", entry));
    }

    /** 边界统一处理：先 flush thinking，再 finalize textSegment（会顺带 flush delta）。 */
    void onBoundary() {
        flushThinking();
        finalizeTextSegment();
    }

    // ── 外部事件 ────────────────────────────────────────────────────────────

    /** 工具 start/patch、plan、subagent 等"由服务方组装并下发"的事件写入 eventLog。 */
    void recordExternal(String name, Object data) {
        eventLog.add(new AiSseEvent(name, data));
    }
}
