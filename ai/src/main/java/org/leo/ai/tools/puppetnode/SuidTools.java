package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SUID / SGID / Capabilities 枚举工具。逻辑在 SuidCapabilityService（core 模块）。
 */
@Component
public class SuidTools {

    @Tool("枚举 puppet 侧（Linux）具有 SUID/SGID 位的可执行文件及具有特殊 Capabilities 的文件，辅助提权路径分析。"
            + "op: 0=SUID, 1=SGID, 2=Capabilities, 3=全部（默认）。仅 Linux 有效。")
    public Map<String, Object> enumerateSuidCapabilities(
            @P("枚举类型：0=SUID, 1=SGID, 2=Capabilities, 3=全部") int op) throws Exception {
        JavaPuppetNode node = node();
        switch (op) {
            case 0: return node.listSuidFiles();
            case 1: return node.listSgidFiles();
            case 2: return node.listFileCapabilities();
            default: return node.listAllSuidCaps();
        }
    }

    private JavaPuppetNode node() throws Exception {
        return PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
    }
}
