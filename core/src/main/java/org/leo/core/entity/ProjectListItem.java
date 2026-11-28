package org.leo.core.entity;

/** 同一条列表查询返回的项目资料与入口节点数。 */
public record ProjectListItem(String projectId, int entryNodeCount, Project project) {
}
