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
 * 进程管理工具（精简版）
 * <p>
 * 对外仅暴露 2 个 @Tool 方法：
 * <ul>
 *   <li>{@link #findProcesses} — 查找进程（不传过滤条件则返回全量）</li>
 *   <li>{@link #killProcess} — 终止指定进程</li>
 * </ul>
 * <p>
 * 自动适配 Linux（/proc 文件系统）和 Windows（wmic/tasklist）。
 */
@Component
public class ProcessTools {

    private static final Logger log = LoggerFactory.getLogger(ProcessTools.class);

    @Tool("查找 puppet 侧操作系统进程，自动适配 Windows / Linux。支持三种过滤维度（可组合，全部不传则返回全量）：\n"
            + "• name — 进程名/命令行关键字模糊匹配（不区分大小写）\n"
            + "• pid — 精确 PID（-1 表示不过滤）\n"
            + "• port — 监听端口号反查进程（-1 表示不过滤）\n"
            + "返回结构化数组：pid、name、user、cmd（完整命令行）、ppid（父进程）、memKb（内存 KB）。\n"
            + "典型用法：查 java 进程 → name='java'；查谁监听 8080 → port=8080；全量列举 → 不传任何过滤参数。")
    public Map<String, Object> findProcesses(
            @P("进程名/命令行关键字（模糊匹配，不区分大小写）") String name,
            @P("精确 PID（-1 表示不过滤）") int pid,
            @P("监听端口号（-1 表示不过滤）") int port) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        // 全部过滤条件为空/默认值时，底层返回全量进程列表
        if ((name == null || name.isBlank()) && pid <= 0 && port <= 0) {
            return node.listProcesses();
        }
        return node.findProcesses(name, pid, port);
    }

    @Tool("终止 puppet 侧指定 PID 的进程，自动适配 Windows / Linux。"
            + "force=true 时强制终止。⚠️ 操作不可逆，请确认 PID 正确。")
    public Map<String, Object> killProcess(
            @P("要终止的进程 PID") int pid,
            @P("是否强制终止（true=SIGKILL/taskkill /F）") boolean force) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.killProcess(pid, force);
    }
}
