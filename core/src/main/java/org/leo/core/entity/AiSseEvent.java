package org.leo.core.entity;

/**
 * 统一的 SSE 事件载体，由各状态类的 offer* 方法写入，SSE drain 线程消费后推送到客户端。
 *
 * <p>事件类型（{@link #name}）与 SSE {@code event:} 字段对应，当前定义：
 * <ul>
 *   <li>{@code node}    — 节点事件，payload 含 {@code kind} 字段（thinking / tool / plan / subtask）</li>
 *   <li>{@code patch}   — 节点补丁，payload 含 {@code kind} 字段（tool 更新结果）</li>
 *   <li>{@code confirm} — 工具确认请求（{@link AiConfirmationRequest}）</li>
 *   <li>{@code status}  — 任务状态文本（{@link String}）</li>
 *   <li>{@code warn}    — 系统告警文本（{@link String}）</li>
 *   <li>{@code subagent} — 子 Agent 派发事件（序列化的 AiSubagentInvocation）</li>
 * </ul>
 *
 * <p>{@link #subagentInvocationId} 非 null 时表示该事件来自某个被派发的子 Agent，
 * 前端可据此把事件聚合到对应的 dispatchSubAgent 工具调用气泡里折叠展示。
 *
 * @param seq 当前 AI 线程内递增序号，用于断线后补拉
 * @param timestamp 事件产生时间
 * @param name 事件类型名称
 * @param data 事件数据（JSON 序列化由 SseEmitter 处理）
 * @param subagentInvocationId 关联的子 Agent 派发 ID，null 表示父 Agent 直接产出
 */
public record AiSseEvent(long seq, long timestamp, String name, Object data, String subagentInvocationId) {

    /** 便捷构造：seq=0、当前时间戳，主要给单元测试或即时事件使用。 */
    public AiSseEvent(String name, Object data) {
        this(0L, System.currentTimeMillis(), name, data, null);
    }

    public AiSseEvent(String name, Object data, String subagentInvocationId) {
        this(0L, System.currentTimeMillis(), name, data, subagentInvocationId);
    }

    /**
     * 在保留 seq/timestamp/name/data 的前提下挂上 subagentInvocationId 标签。
     * 由 {@code SubagentStateAccessor} 在事件转发到父队列前调用。
     */
    public AiSseEvent withSubagent(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) return this;
        return new AiSseEvent(seq, timestamp, name, data, invocationId);
    }
}
