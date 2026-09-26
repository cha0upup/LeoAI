package org.leo.ai.agent;

/** Agent 工具的规范化元数据。 */
public record AiToolDescriptor(
        String name,
        AiToolKind kind,
        AiToolOperation operation,
        boolean terminal,
        boolean exclusive,
        boolean parallelizable,
        boolean business) {

    public static AiToolDescriptor conservative(String name) {
        return new AiToolDescriptor(name, AiToolKind.COMMAND,
                AiToolOperation.WRITE, false, false, false, true);
    }

}
