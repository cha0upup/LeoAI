package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system scheduled tasks.
 */
public interface ScheduledTaskCapable {

    Map<String, Object> listScheduledTasks() throws Exception;

    Map<String, Object> queryScheduledTask(String taskName) throws Exception;

    Map<String, Object> createScheduledTaskWindows(String taskName, String command, String schedule,
                                                   String modifier, String startTime, String startDate,
                                                   String runAs, boolean force) throws Exception;

    Map<String, Object> createScheduledTaskLinux(String cronExpression, String command) throws Exception;

    Map<String, Object> deleteScheduledTask(String taskName) throws Exception;

    Map<String, Object> runScheduledTask(String taskName) throws Exception;

    Map<String, Object> enableScheduledTask(String taskName) throws Exception;

    Map<String, Object> disableScheduledTask(String taskName) throws Exception;
}
