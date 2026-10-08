package org.leo.web.dto.platform.agent;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.leo.core.entity.Agent;

/** 目录专用视图；子机标记不写入主机配置或数据库。 */
public record AgentDirectoryEntry(@JsonUnwrapped Agent agent, boolean hasChildren) {}
