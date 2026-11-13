package org.leo.web.dto.platform.agent;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.leo.core.entity.Agent;
import org.leo.web.application.agent.AgentDirectoryService.Entry;

import java.util.List;

/** 目录专用视图；子机标记不写入主机配置或数据库。 */
public record AgentDirectoryEntry(@JsonUnwrapped Agent agent, boolean hasChildren) {
    public static List<AgentDirectoryEntry> from(List<Entry> entries) {
        return entries.stream().map(entry -> new AgentDirectoryEntry(entry.agent(), entry.hasChildren())).toList();
    }
}
