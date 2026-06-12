package org.leo.web.dto.puppetnode.ai;

/**
 * 新建 AI 对话线程请求。
 *
 * @param sessionId puppet session ID
 * @param title     线程标题（可空，留空时自动生成）
 * @param configId  指定 AI 通道；null 时使用激活通道
 * @param mode      执行模式 plan / execute / auto（可空 → auto）
 */
public record AiThreadCreateRequest(String sessionId, String title, Integer configId,
                                    String mode) {
}
