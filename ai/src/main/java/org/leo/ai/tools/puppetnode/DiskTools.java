package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 磁盘与卷枚举工具。逻辑在 MountDiskService（core 模块）。
 */
@Component
public class DiskTools {

    @Tool("枚举 puppet 侧已挂载的磁盘、分区和卷，返回各分区的容量、已用/可用空间、文件系统类型和挂载点。自动适配 Windows / macOS / Linux。")
    public Map<String, Object> listDisks() throws Exception {
        return node().listMountDisks();
    }

    private JavaPuppetNode node() throws Exception {
        return PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
    }
}
