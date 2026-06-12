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
 * 操作系统服务管理工具
 * <p>
 * 在 puppet 侧管理系统服务（Windows sc.exe / macOS launchctl / Linux systemctl）。
 */
@Component
public class ServiceManagerTools {

    private static final Logger log = LoggerFactory.getLogger(ServiceManagerTools.class);

    @Tool("列举 puppet 侧所有操作系统服务及运行状态，自动适配 Windows / macOS / Linux。"
            + "返回服务名、显示名、运行状态、启动类型等。"
            + "结果可能较多（数百个服务），建议先 list 再用 queryService 查看单个服务详情。")
    public Map<String, Object> listServices() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.listServices();
    }

    @Tool("查询 puppet 侧指定服务的详细信息，自动适配 Windows / macOS / Linux。"
            + "返回二进制路径、启动类型、运行身份、PID、内存、unit 文件路径等完整配置。")
    public Map<String, Object> queryService(
            @P("服务名称（Windows 如 Spooler，Linux 如 sshd.service 或 sshd）") String serviceName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.queryService(serviceName);
    }

    @Tool("启动/停止/重启 puppet 侧的系统服务，自动适配 Windows / macOS / Linux。"
            + "⚠️ 写操作，停止关键服务可能影响系统稳定性。")
    public Map<String, Object> controlService(
            @P("服务名称") String serviceName,
            @P("操作类型：start / stop / restart") String operation) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if ("start".equals(operation)) {
            return node.startService(serviceName);
        } else if ("stop".equals(operation)) {
            return node.stopService(serviceName);
        } else if ("restart".equals(operation)) {
            return node.restartService(serviceName);
        } else {
            throw new IllegalArgumentException("unsupported operation: " + operation + " (use start/stop/restart)");
        }
    }

    @Tool("设置 puppet 侧服务的开机启动状态，自动适配 Windows / macOS / Linux。")
    public Map<String, Object> toggleServiceAutoStart(
            @P("服务名称") String serviceName,
            @P("true=启用开机启动, false=禁用") boolean enable) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (enable) {
            return node.enableService(serviceName);
        } else {
            return node.disableService(serviceName);
        }
    }

    @Tool("在 puppet 侧创建新的系统服务，自动适配 Windows / macOS / Linux。"
            + "macOS / Linux 会返回配置模板和安装步骤，需配合文件工具写入后生效。"
            + "⚠️ 高危写操作，创建前确认参数。")
    public Map<String, Object> createService(
            @P("服务名称") String serviceName,
            @P("可执行文件路径") String binPath,
            @P("显示名称（可选）") String displayName,
            @P("启动类型：auto/demand/disabled（默认 demand）") String startType) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.createService(serviceName, binPath, displayName, startType);
    }

    @Tool("删除 puppet 侧的系统服务，自动适配 Windows / macOS / Linux。"
            + "⚠️ 不可逆操作，删除前先用 queryService 确认目标。")
    public Map<String, Object> deleteService(
            @P("服务名称") String serviceName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.deleteService(serviceName);
    }
}
