package org.leo.ai.tools.agentnode;

import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.service.DatabaseConnectionProfileException;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.AgentAuditService;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node-AI tools for persisting database credentials discovered on a Agent.
 * Returned views are sanitized by the shared profile service.
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public final class DatabaseConnectionTools {

    private final DatabaseConnectionProfileService profileService;
    private final AgentAuditService auditService;
    private final SqlDialectRegistry sqlDialectRegistry;

    public DatabaseConnectionTools(DatabaseConnectionProfileService profileService,
                                   AgentAuditService auditService,
                                   SqlDialectRegistry sqlDialectRegistry) {
        this.profileService = profileService;
        this.auditService = auditService;
        this.sqlDialectRegistry = sqlDialectRegistry;
    }

    @Tool("""
            列出当前 Agent 已保存的数据库连接配置。返回 connectionId、名称、启用状态和不含密码的 connection。
            在编辑、删除或使用已保存连接前先调用本工具确认 connectionId。
            """)
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> listDatabaseConnections() {
        ToolScope scope = scope();
        return profileService.listByAgent(scope.userId(), scope.agentId());
    }

    @Tool("""
            获取当前系统支持的数据库方言目录，包括 dialect、名称、默认端口、连接变体和字段。
            新增或修改数据库连接前先调用本工具。只能使用目录返回的 dialect；未列出的厂商必须使用 generic + custom，
            通过 runtimeOptions.java(driverClass、jdbcUrl) 或 runtimeOptions.php(pdoDriver、dsn) 连接。
            """)
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> getDatabaseDialectCatalog() {
        return sqlDialectRegistry.getDialectInfos();
    }

    @Tool("""
            为当前 Agent 新增数据库连接配置，用于保存刚收集到的连接信息。
            保存前先调用 getDatabaseDialectCatalog，严格使用目录返回的 dialect，禁止自行创造方言 ID。
            当前目录是方言与 Runtime 支持的唯一事实来源；不得依赖静态方言列表。
            标准连接使用 connectionMode="standard"；SQLite 使用 file；Oracle 使用 service 或 sid。
            达梦 DM(dialect="dm") 和 KingbaseES(dialect="kingbasees") 已内置 Java Standard JDBC；其 PHP Standard 支持以目录 runtimeSupport 为准。
            目录中没有的数据库必须使用 dialect="generic"、connectionMode="custom"，并至少完整提供：
            Java: runtimeOptions.java={"driverClass":"厂商 JDBC Driver 类","jdbcUrl":"厂商 JDBC URL"}；或
            PHP: runtimeOptions.php={"pdoDriver":"PDO Driver","dsn":"厂商 DSN"}。
            generic 可配置 dialectOptions.testSql，仅支持连接测试和原始 querySql/execSql，不支持结构化库表管理。
            config 还可包含 connectionName、description、remark、status、timeoutSeconds。
            返回可供 querySql/execSql 使用的 connectionId，响应不会回显密码。
            """)
    public Map<String, Object> createDatabaseConnection(Map<String, Object> config) {
        ToolScope scope = scope();
        return auditedMutation(scope, "DATABASE_CONNECTION_CREATE", "AI新增数据库配置",
                "create", config,
                () -> profileService.create(scope.userId(), scope.agentId(), config));
    }

    @Tool("""
            编辑当前 Agent 的数据库连接配置。connectionId 必填；patch 只传需要修改的字段，
            可修改 connectionName、description、remark、status、timeoutSeconds，
            以及 patch.connection 中的 host、port、database、username、password 等连接字段。
            修改 dialect、connectionMode、variant 或 runtimeOptions 前先调用 getDatabaseDialectCatalog；禁止使用目录之外的方言。
            未传字段保持原值；不修改密码时不要传 password，返回内容不会回显密码。
            """)
    public Map<String, Object> updateDatabaseConnection(String connectionId,
                                                        Map<String, Object> patch) {
        ToolScope scope = scope();
        return auditedMutation(scope, "DATABASE_CONNECTION_UPDATE", "AI编辑数据库配置",
                connectionId, patch,
                () -> profileService.update(
                        scope.userId(), scope.agentId(), connectionId, patch));
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("""
            删除当前 Agent 已保存的数据库连接配置。先调用 listDatabaseConnections 确认 connectionId。
            删除成功后该 connectionId 将不能再用于 querySql 或 execSql。
            """)
    public Map<String, Object> deleteDatabaseConnection(String connectionId) {
        ToolScope scope = scope();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("connectionId", connectionId);
        return auditedMutation(scope, "DATABASE_CONNECTION_DELETE", "AI删除数据库配置",
                connectionId, params, () -> {
                    profileService.delete(scope.userId(), scope.agentId(), connectionId);
                    return Map.of("connectionId", connectionId, "deleted", true);
                });
    }

    private ToolScope scope() {
        String sessionId = AiToolContext.requireSessionId();
        String agentId = AgentNodeSessionUtils.requireAgentId(sessionId);
        String userId = AgentNodeSessionUtils.requireUserId(sessionId);
        AbstractAgentNode node = AgentNodeSessionUtils.getSession(sessionId).getAgentNode();
        return new ToolScope(sessionId, agentId, userId, node);
    }

    private <T> T auditedMutation(ToolScope scope,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> params,
                                  Mutation<T> mutation) {
        Map<String, Object> auditParams = new LinkedHashMap<String, Object>();
        auditParams.put("agentId", scope.agentId());
        if (params != null) auditParams.putAll(params);
        try {
            T result = mutation.run();
            auditService.logSuccess(scope.sessionId(), scope.node(), operationType,
                    operationName, operationPath, auditParams, operationName + "成功");
            return result;
        } catch (DatabaseConnectionProfileException error) {
            auditService.logFailure(scope.sessionId(), scope.node(), operationType,
                    operationName, operationPath, auditParams, error.getMessage());
            throw toolFailure(error);
        }
    }

    private AiToolException toolFailure(DatabaseConnectionProfileException error) {
        return switch (error.getKind()) {
            case VALIDATION -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_INVALID", error.getMessage(),
                    "检查连接字段格式，或先调用 listDatabaseConnections 获取当前配置。");
            case NOT_FOUND -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_NOT_FOUND", error.getMessage(),
                    "调用 listDatabaseConnections 获取有效 connectionId。");
            case FORBIDDEN -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_AGENT_MISMATCH", error.getMessage(),
                    "只使用当前 Agent 列表中返回的 connectionId。");
            case PERSISTENCE -> AiToolException.systemRetryable(
                    "DATABASE_PROFILE_PERSISTENCE_FAILED", error.getMessage(), error);
        };
    }

    private record ToolScope(String sessionId,
                             String agentId,
                             String userId,
                             AbstractAgentNode node) {}

    @FunctionalInterface
    private interface Mutation<T> {
        T run();
    }
}
