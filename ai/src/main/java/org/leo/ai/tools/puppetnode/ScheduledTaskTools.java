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
 * 操作系统计划任务管理工具
 * <p>
 * 在 puppet 侧管理计划任务（Windows schtasks / Linux crontab+systemd timer）。
 * 支持列举、查询、创建、删除、运行、启用/禁用。
 */
@Component
public class ScheduledTaskTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTools.class);

    @Tool("列举 puppet 侧所有操作系统计划任务，自动适配 Windows / Linux。"
            + "返回任务名、状态、下次运行时间、命令等。"
            + "结果可能较多，建议先 list 再用 queryScheduledTask 查看单个任务详情。")
    public Map<String, Object> listScheduledTasks() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.listScheduledTasks();
    }

    @Tool("查询 puppet 侧指定计划任务的详细信息，自动适配 Windows / Linux。"
            + "Windows 返回完整触发器、操作、条件等属性；Linux 在所有 cron 源中模糊搜索匹配条目。")
    public Map<String, Object> queryScheduledTask(
            @P("任务名称（Windows 为完整路径如 \\Microsoft\\Windows\\...，Linux 为关键字模糊匹配）") String taskName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.queryScheduledTask(taskName);
    }

    @Tool("在 puppet 侧创建 Windows 计划任务。"
            + "schedule 支持：MINUTE、HOURLY、DAILY、WEEKLY、MONTHLY、ONCE、ONSTART、ONLOGON、ONIDLE。"
            + "modifier 细化频率，如 schedule=MINUTE modifier=5 表示每 5 分钟。"
            + "⚠️ 写操作，创建前确认参数正确。force=true 覆盖同名任务。")
    public Map<String, Object> createScheduledTaskWindows(
            @P("任务名称（如 \\MyTasks\\Backup）") String taskName,
            @P("要执行的命令或程序路径") String command,
            @P("计划类型：MINUTE/HOURLY/DAILY/WEEKLY/MONTHLY/ONCE/ONSTART/ONLOGON/ONIDLE") String schedule,
            @P("频率修饰（如 5 表示每5个单位），可为空") String modifier,
            @P("开始时间 HH:mm 格式，可为空") String startTime,
            @P("开始日期 yyyy/MM/dd 格式，可为空") String startDate,
            @P("运行身份（如 SYSTEM），可为空") String runAs,
            @P("是否强制覆盖已有同名任务（默认 true）") boolean force) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.createScheduledTaskWindows(taskName, command, schedule, modifier, startTime, startDate, runAs, force);
    }

    @Tool("在 puppet 侧创建 Linux cron 计划任务，追加到当前用户的 crontab。"
            + "cronExpression 为标准 5 字段格式：分 时 日 月 周（如 '*/5 * * * *' 表示每5分钟）。"
            + "⚠️ 写操作，会直接修改用户 crontab。")
    public Map<String, Object> createScheduledTaskLinux(
            @P("cron 表达式（5 字段：分 时 日 月 周，如 '0 2 * * *' 表示每天凌晨2点）") String cronExpression,
            @P("要执行的命令") String command) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.createScheduledTaskLinux(cronExpression, command);
    }

    @Tool("删除 puppet 侧的计划任务，自动适配 Windows / Linux。"
            + "⚠️ 不可逆操作，删除前先用 queryScheduledTask 确认目标。")
    public Map<String, Object> deleteScheduledTask(
            @P("任务名称（Windows 为路径，Linux 为命令关键字）") String taskName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.deleteScheduledTask(taskName);
    }

    @Tool("立即运行 puppet 侧指定的 Windows 计划任务（仅 Windows）。"
            + "不改变任务的计划设置，只是立即触发一次执行。")
    public Map<String, Object> runScheduledTask(
            @P("任务名称") String taskName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.runScheduledTask(taskName);
    }

    @Tool("启用或禁用 puppet 侧 Windows 计划任务。"
            + "enable=true 启用，enable=false 禁用。仅 Windows 支持。")
    public Map<String, Object> toggleScheduledTask(
            @P("任务名称") String taskName,
            @P("true=启用, false=禁用") boolean enable) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (enable) {
            return node.enableScheduledTask(taskName);
        } else {
            return node.disableScheduledTask(taskName);
        }
    }
}
