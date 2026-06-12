package org.leo.web.dto.puppetnode.ai;

/**
 * 切换 AI 对话线程执行模式。
 *
 * @param sessionId puppet session ID
 * @param threadId  目标 AI 线程 ID
 * @param mode      执行模式 plan / execute / auto；为空时保留原值
 */
public record AiThreadModeRequest(String sessionId, String threadId, String mode) {
}
