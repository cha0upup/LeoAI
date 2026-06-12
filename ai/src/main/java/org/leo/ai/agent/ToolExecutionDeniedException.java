package org.leo.ai.agent;

/**
 * 用户拒绝工具执行或确认超时时抛出。
 *
 * <p>LangChain4j 会捕获此异常并将其作为工具执行错误返回给 AI，
 * AI 可据此调整策略（例如提示用户手动操作或跳过该步骤）。
 */
public class ToolExecutionDeniedException extends RuntimeException {

    public ToolExecutionDeniedException(String message) {
        super(message);
    }

    public ToolExecutionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
