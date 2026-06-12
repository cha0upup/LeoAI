package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MountDiskTools {

    @Tool("枚举 puppet 侧当前挂载的磁盘和文件系统，自动适配 Windows / Linux。返回设备名、挂载点、文件系统类型、总容量、已用、可用空间。")
    public Map<String, Object> listMountDisks() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listMountDisks();
    }
}
