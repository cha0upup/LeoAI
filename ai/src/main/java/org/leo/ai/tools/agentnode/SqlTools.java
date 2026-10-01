package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.service.DatabaseConnectionProfileException;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.AgentAuditService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class SqlTools {

    private static final Set<String> READ_ONLY_KEYWORDS = Set.of(
            "select", "show", "describe", "desc"
    );

    private final AgentAuditService auditService;
    private final DatabaseConnectionProfileService profileService;

    public SqlTools(AgentAuditService auditService,
                    DatabaseConnectionProfileService profileService) {
        this.auditService = auditService;
        this.profileService = profileService;
    }

    @Tool("""
            执行 SQL 语句（允许写入和结构变更）。connection 可直接提供完整连接字段，
            也可传 {"connectionId":"已保存配置ID"} 使用当前 Agent 已启用的数据库配置。
            验证连接、枚举库表、查询或提取证据；只读查询优先使用 querySql。
            """)
    public Map<String, Object> execSql(Map<String, Object> connection, String sqlScript) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        SqlCapable node = AgentNodeSessionUtils.requireCapability(sessionId, SqlCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        ResolvedConnection resolved = resolveConnection(sessionId, connection);
        DatabaseConnectionSpec connectionSpec = resolved.spec();
        Map<String, Object> auditParams = sqlAuditParams(
                sessionId, resolved.connectionId(), connectionSpec, sqlScript);
        String operationPath = sqlOperationPath(connectionSpec, sqlScript);
        try {
            Map<String, Object> result = node.executeSql(connectionSpec, sqlScript);
            auditService.logSuccess(sessionId, auditNode, "SQL_EXEC", "AI执行SQL", operationPath,
                    auditParams, "AI执行SQL成功");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "SQL_EXEC", "AI执行SQL", operationPath,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    @Tool("""
            执行单条只读 SQL 查询，仅允许 SELECT、SHOW、DESCRIBE/DESC。
            connection 可直接提供完整连接字段，也可传 {"connectionId":"已保存配置ID"}
            使用当前 Agent 已启用的数据库配置。P0 安全边界暂不接受 WITH/CTE；
            写入、结构变更或无法明确证明只读的 SQL 请使用 execSql。
            """)
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> querySql(Map<String, Object> connection, String sqlScript) throws Exception {
        String violation = detectSqlViolation(sqlScript);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        String sessionId = AiToolContext.requireSessionId();
        SqlCapable node = AgentNodeSessionUtils.requireCapability(sessionId, SqlCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        ResolvedConnection resolved = resolveConnection(sessionId, connection);
        DatabaseConnectionSpec connectionSpec = resolved.spec();
        Map<String, Object> auditParams = sqlAuditParams(
                sessionId, resolved.connectionId(), connectionSpec, sqlScript);
        String operationPath = sqlOperationPath(connectionSpec, sqlScript);
        try {
            Map<String, Object> result = node.executeSql(connectionSpec, sqlScript);
            auditService.logSuccess(sessionId, auditNode, "SQL_QUERY", "AI查询SQL", operationPath,
                    auditParams, "AI查询SQL成功");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "SQL_QUERY", "AI查询SQL", operationPath,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    /**
     * Conservatively proves that a script is one read-only statement.
     * Comments and quoted literals/identifiers are scanned character by
     * character so semicolons inside them are not treated as delimiters.
     * CTEs are intentionally rejected until a real SQL parser is introduced.
     *
     * @return violation reason, or {@code null} when the statement is allowed
     */
    private String detectSqlViolation(String sql) {
        if (sql == null || sql.isBlank()) {
            return "querySql 的 SQL 不能为空。";
        }
        SqlScanResult scan = scanSql(sql);
        if (scan.unterminated()) {
            return "querySql 检测到未闭合的注释或引号，已拒绝执行。";
        }
        if (scan.statementCount() != 1) {
            return "querySql 只允许一条 SQL（检测到 " + scan.statementCount()
                    + " 条语句），已拒绝执行。如需写操作请使用 execSql。";
        }
        String firstKeyword = scan.firstKeyword();
        if ("with".equals(firstKeyword)) {
            return "querySql 暂不允许 WITH/CTE，因为其中可能包含写入语句；请改写为单条 SELECT，"
                    + "或在确认需要写入后使用 execSql。";
        }
        if (!READ_ONLY_KEYWORDS.contains(firstKeyword)) {
            return "querySql 仅允许 SELECT、SHOW、DESCRIBE/DESC，检测到 "
                    + (firstKeyword == null ? "未知语句" : firstKeyword.toUpperCase(Locale.ROOT))
                    + "，已拒绝执行。如需写操作请使用 execSql。";
        }
        return null;
    }

    private SqlScanResult scanSql(String sql) {
        StringBuilder visible = new StringBuilder(sql.length());
        int statementCount = 0;
        boolean statementHasContent = false;
        ScanState state = ScanState.NORMAL;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-') {
                        state = ScanState.LINE_COMMENT;
                        visible.append(' ');
                        i++;
                    } else if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        visible.append(' ');
                        i++;
                    } else if (current == '\'') {
                        state = ScanState.SINGLE_QUOTE;
                        visible.append(' ');
                    } else if (current == '"') {
                        state = ScanState.DOUBLE_QUOTE;
                        visible.append(' ');
                    } else if (current == '`') {
                        state = ScanState.BACKTICK;
                        visible.append(' ');
                    } else if (current == '[') {
                        state = ScanState.BRACKET_IDENTIFIER;
                        visible.append(' ');
                    } else if (current == ';') {
                        if (statementHasContent) {
                            statementCount++;
                            statementHasContent = false;
                        }
                        visible.append(' ');
                    } else {
                        visible.append(current);
                        if (!Character.isWhitespace(current)) statementHasContent = true;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                        i++;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        i++;
                    } else if (current == '\'') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        i++;
                    } else if (current == '"') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                    }
                }
                case BACKTICK -> {
                    if (current == '`' && next == '`') {
                        i++;
                    } else if (current == '`') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                    }
                }
                case BRACKET_IDENTIFIER -> {
                    if (current == ']' && next == ']') {
                        i++;
                    } else if (current == ']') {
                        state = ScanState.NORMAL;
                        visible.append(' ');
                    }
                }
            }
        }
        if (statementHasContent) statementCount++;
        boolean unterminated = state != ScanState.NORMAL && state != ScanState.LINE_COMMENT;
        String normalized = visible.toString().trim();
        String firstKeyword = normalized.isEmpty()
                ? null : normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return new SqlScanResult(statementCount, firstKeyword, unterminated);
    }

    private enum ScanState {
        NORMAL, LINE_COMMENT, BLOCK_COMMENT, SINGLE_QUOTE,
        DOUBLE_QUOTE, BACKTICK, BRACKET_IDENTIFIER
    }

    private record SqlScanResult(int statementCount,
                                 String firstKeyword,
                                 boolean unterminated) {}

    private ResolvedConnection resolveConnection(String sessionId,
                                                 Map<String, Object> connection) {
        String connectionId = text(connection == null ? null : connection.get("connectionId"));
        if (connectionId == null) {
            return new ResolvedConnection(null, DatabaseConnectionSpec.fromMap(connection));
        }
        try {
            DatabaseConnectionSpec spec = profileService.resolveActive(
                    AgentNodeSessionUtils.requireUserId(sessionId),
                    AgentNodeSessionUtils.requireAgentId(sessionId),
                    connectionId);
            return new ResolvedConnection(connectionId, spec);
        } catch (DatabaseConnectionProfileException error) {
            throw switch (error.getKind()) {
                case VALIDATION -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_INVALID", error.getMessage(),
                        "检查配置是否已启用，或调用 listDatabaseConnections 查看状态。");
                case NOT_FOUND -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_NOT_FOUND", error.getMessage(),
                        "调用 listDatabaseConnections 获取有效 connectionId。");
                case FORBIDDEN -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_AGENT_MISMATCH", error.getMessage(),
                        "只使用当前 Agent 返回的 connectionId。");
                case PERSISTENCE -> AiToolException.systemRetryable(
                        "DATABASE_PROFILE_RESOLUTION_FAILED", error.getMessage(), error);
            };
        }
    }

    private Map<String, Object> sqlAuditParams(String sessionId,
                                               String connectionId,
                                               DatabaseConnectionSpec connection,
                                               String sqlScript) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("sessionId", sessionId);
        if (connectionId != null) params.put("connectionId", connectionId);
        Map<String, Object> safeConnection = new HashMap<>(connection.toMap());
        safeConnection.remove("password");
        params.put("connection", safeConnection);
        params.put("sql", sqlScript);
        return params;
    }

    private String sqlOperationPath(DatabaseConnectionSpec connection, String sqlScript) {
        String sql = truncate(sqlScript, 180);
        String target = connection.getDialect() + "://"
                + (connection.getHost() == null ? "local" : connection.getHost())
                + (connection.getDatabase() == null ? "" : "/" + connection.getDatabase());
        return sql == null || sql.isBlank() ? target : target + " | " + sql;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record ResolvedConnection(String connectionId, DatabaseConnectionSpec spec) {}
}
