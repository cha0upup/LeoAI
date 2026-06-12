package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WiFi 配置文件与密码提取工具。逻辑在 WifiProfileService（core 模块）。
 */
@Component
public class WifiTools {

    @Tool("列举 puppet 侧已保存的 WiFi 网络名称。自动适配 Windows / macOS / Linux。")
    public Map<String, Object> listWifiProfiles() throws Exception {
        return node().listWifiProfiles();
    }

    @Tool("提取 puppet 侧指定 WiFi 网络的密码。自动适配 Windows / macOS / Linux。")
    public Map<String, Object> getWifiPassword(
            @P("WiFi 网络名称（SSID）") String profileName) throws Exception {
        return node().getWifiProfileDetail(profileName);
    }

    @Tool("批量提取 puppet 侧所有已保存 WiFi 网络的密码。自动适配 Windows / macOS / Linux。")
    public Map<String, Object> dumpAllWifiPasswords() throws Exception {
        return node().dumpAllWifiPasswords();
    }

    private JavaPuppetNode node() throws Exception {
        return PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
    }
}
