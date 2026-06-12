package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SuidCapabilityTools {

    @Tool("一键枚举 puppet 侧所有 SUID 文件、SGID 文件和具有特殊 Linux Capabilities 的文件。适用于 Linux 提权路径检测。返回文件路径、权限位和所属用户/组。")
    public Map<String, Object> enumerateSuidCapabilities() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listAllSuidCaps();
    }
}
