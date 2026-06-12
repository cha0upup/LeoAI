package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserAccountTools {

    @Tool("枚举 puppet 侧操作系统本地用户列表，自动适配 Windows / Linux。返回用户名、UID/SID、主目录、Shell 等信息。")
    public Map<String, Object> listUsers() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listUsers();
    }

    @Tool("枚举 puppet 侧操作系统本地用户组列表，自动适配 Windows / Linux。")
    public Map<String, Object> listGroups() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listGroups();
    }

    @Tool("查询当前 WebShell 进程的运行用户信息（whoami 等效）。返回用户名、权限级别、所属组。")
    public Map<String, Object> getCurrentUserInfo() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.whoami();
    }

    @Tool("查询指定用户名的详细信息，包含 UID、主目录、Shell、所属组、登录历史等。")
    public Map<String, Object> queryUser(
            @P("要查询的用户名") String username) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.queryUser(username);
    }
}
