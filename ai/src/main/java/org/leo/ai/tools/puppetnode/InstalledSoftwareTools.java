package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class InstalledSoftwareTools {

    @Tool("枚举 puppet 侧已安装的所有软件包，自动适配 Windows（注册表）/ Linux（dpkg/rpm/apk）。适用于识别可利用的过时软件版本。")
    public Map<String, Object> listInstalledSoftware() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listAllSoftware();
    }

    @Tool("在 puppet 侧已安装软件列表中按关键词搜索，返回匹配的软件名和版本。")
    public Map<String, Object> searchInstalledSoftware(
            @P("搜索关键词，如 java、tomcat、openssl") String keyword) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.searchSoftware(keyword);
    }
}
