package org.leo.web.service;

import org.leo.core.entity.AiSseEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 累积 LangChain4j 流式回调中的可见 token、思考 token、工具/Plan/子任务等事件，
 * 在 thinking / tool / complete 等边界把待发缓冲落成 SSE timeline 节点事件。
 *
 * <p>四块缓冲：
 * <ul>
 *   <li>{@code reply}        — 完整可见回复文本，给 finishRun / persistMessage 用</li>
 *   <li>{@code delta}        — 自上次 {@link #flushDelta()} 以来的可见 token，攒批后以
 *                              {@code delta} 事件下发，只服务于前端直播，不入 eventLog</li>
 *   <li>{@code textSegment}  — 自上次 {@link #finalizeTextSegment()} 以来的可见 token，
 *                              在 thinking / tool / complete 边界封装成
 *                              {@code node{kind:"text"}} 事件并写入 eventLog 用于持久化</li>
 *   <li>{@code thinking}     — 思考 token，{@link #flushThinking()} 时产出
 *                              {@code node{kind:"thinking"}} 事件并写入 eventLog</li>
 * </ul>
 *
 * <p>下发使用调用方传入的 {@link Sink}，由调用方负责 SSE 安全下发以及 thread/state 内部
 * 的 {@code recordSseEvent} 持久化（用于 SSE 断线后重放）。本类只关心累积与边界化。
 *
 * <p>线程安全：本类不可重入。每次 AI 流式调用应使用独立实例，调用顺序由 LangChain4j 流式回调
 * 串行保证；{@code eventLog} 用 {@link CopyOnWriteArrayList} 仅是为了让 SSE drain 线程并发读取
 * 时不会 ConcurrentModificationException。
 */
public final class AiTimelineRecorder {

    /** 事件下发回调；实现方负责 SSE 安全下发 + thread/state 端 {@code recordSseEvent} 持久化。 */
    @FunctionalInterface
    public interface Sink {
        void emit(String name, Object data);
    }

    private final Sink sink;
    private final StringBuilder reply       = new StringBuilder();
    private final StringBuilder delta       = new StringBuilder();
    private final StringBuilder thinking    = new StringBuilder();
    private final StringBuilder textSegment = new StringBuilder();
    private final List<AiSseEvent> eventLog = new CopyOnWriteArrayList<>();

    public AiTimelineRecorder(Sink sink) {
        this.sink = sink;
    }

    /** SSE 重放 / 持久化用的事件序列；并发安全，可由 SSE drain 线程读取。 */
    public List<AiSseEvent> eventLog() {
        return eventLog;
    }

    /** 完整可见回复文本，用于 audit / finishRun / persistAssistantMessage。 */
    public String reply() {
        return reply.toString();
    }

    public boolean hasPendingThinking() {
        return thinking.length() > 0;
    }

    public boolean hasPendingText() {
        return textSegment.length() > 0;
    }

    // ── 累积 ────────────────────────────────────────────────────────────────

    /**
     * 进入文本流。若仍有未 flush 的 thinking，先 {@link #flushThinking()} 保证事件顺序为
     * thinking → text；之后 token 同时进入 reply / delta / textSegment 三个缓冲。
     */
    public void appendVisibleDelta(String text) {
        if (text == null || text.isEmpty()) return;
        if (thinking.length() > 0) flushThinking();
        reply.append(text);
        delta.append(text);
        textSegment.append(text);
    }

    /**
     * 进入思考流。若仍有未 finalize 的 textSegment，先 {@link #finalizeTextSegment()}
     * 保证事件顺序为 text → thinking。
     */
    public void appendThinking(String text) {
        if (text == null || text.isEmpty()) return;
        if (textSegment.length() > 0) finalizeTextSegment();
        thinking.append(text);
    }

    // ── 边界 flush ──────────────────────────────────────────────────────────

    /** 把累积的 delta 攒批后以 {@code delta} 事件下发；不入 eventLog（仅直播）。 */
    public void flushDelta() {
        if (delta.length() == 0) return;
        String payload = delta.toString();
        delta.setLength(0);
        sink.emit("delta", payload);
    }

    /** thinking 缓冲落地为 {@code node{kind:"thinking"}} 事件并写入 eventLog。 */
    public void flushThinking() {
        if (thinking.length() == 0) return;
        String content = thinking.toString();
        thinking.setLength(0);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "thinking");
        entry.put("content", content);
        entry.put("timestamp", System.currentTimeMillis());
        sink.emit("node", entry);
        eventLog.add(new AiSseEvent("node", entry));
    }

    /**
     * textSegment 缓冲落地为 {@code node{kind:"text"}} 事件并写入 eventLog。
     * 先 {@link #flushDelta()} 让前端直播流闭合，再发节点；空白片段直接丢弃。
     */
    public void finalizeTextSegment() {
        flushDelta();
        if (textSegment.length() == 0) return;
        String content = textSegment.toString();
        textSegment.setLength(0);
        if (content.isBlank()) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "text");
        entry.put("content", content);
        entry.put("timestamp", System.currentTimeMillis());
        sink.emit("node", entry);
        eventLog.add(new AiSseEvent("node", entry));
    }

    /** 边界统一处理：先 flush thinking，再 finalize textSegment（会顺带 flush delta）。 */
    public void onBoundary() {
        flushThinking();
        finalizeTextSegment();
    }

    // ── 外部事件 ────────────────────────────────────────────────────────────

    /** 工具 start/patch、plan、subagent 等"由服务方组装并下发"的事件写入 eventLog。 */
    public void recordExternal(AiSseEvent event) {
        if (event != null) eventLog.add(event);
    }

    /** 便利方法：用 {@code name + data} 包装为 AiSseEvent 后写入 eventLog。 */
    public void recordExternal(String name, Object data) {
        eventLog.add(new AiSseEvent(name, data));
    }
}
