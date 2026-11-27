package org.leo.core.entity;

/** 同一条列表查询返回的项目资料与入口主机数。 */
public record ProjectListItem(String projectId, int hostCount, Project project) {
}
