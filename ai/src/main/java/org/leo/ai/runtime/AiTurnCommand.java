package org.leo.ai.runtime;

import dev.langchain4j.service.TokenStream;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 提交给统一执行引擎的单轮命令。
 *
 * <p>场景层负责准备 Agent 和持久化 Turn，引擎通过 streamFactory 延迟创建模型流，
 * 因而连 Agent 调用阶段的同步异常也能进入统一失败路径。模型流返回可恢复的协议异常时，
 * recoveryStreamFactory 使用同一 ChatMemory 发起一次续接，而不是重放原始任务。
 */
public record AiTurnCommand(
        String conversationId,
        Object memoryId,
        AiTurnCoordinator.Execution execution,
        Supplier<TokenStream> streamFactory,
        Supplier<TokenStream> recoveryStreamFactory) {

    public static final String RECOVERY_MESSAGE =
            "上一段模型流异常结束。请保留当前对话中已经完成的工具结果和计划进度，"
                    + "从中断点继续原任务；不要重复已经完成的步骤，完成后直接给出结论。";

    public AiTurnCommand {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(streamFactory, "streamFactory");
        Objects.requireNonNull(recoveryStreamFactory, "recoveryStreamFactory");
    }
}
