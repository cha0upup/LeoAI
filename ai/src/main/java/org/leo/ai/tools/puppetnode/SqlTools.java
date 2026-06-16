package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SqlTools {

    private static final java.util.Set<String> DDL_DML_KEYWORDS = java.util.Set.of(
            "insert", "update", "delete", "drop", "truncate", "alter",
            "create", "rename", "replace", "merge", "call", "exec",
            "execute", "grant", "revoke", "lock", "unlock"
    );

    /** 行注释 */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\n]*");
    /** 块注释 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 单引号字符串字面量 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'");

    @Tool("执行 SQL 语句（允许写入和结构变更）。验证连接、枚举库表、查询或提取证据。⚠️ 只读查询优先使用 querySql。")
    public Map<String, Object> execSql(String driverClassName, String jdbcUrl, String user, String password, String sqlScript) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.execSql(driverClassName, jdbcUrl, user, password, sqlScript);
    }

    @Tool("执行只读 SQL 查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH）。拒绝写入或结构变更。")
    public Map<String, Object> querySql(String driverClassName, String jdbcUrl, String user, String password, String sqlScript) throws Exception {
        String violation = detectSqlViolation(sqlScript);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.execSql(driverClassName, jdbcUrl, user, password, sqlScript);
    }

    /**
     * 检测 SQL 是否违反只读约束。
     *
     * <p>两道防线：
     * <ol>
     *   <li>多语句检测：去掉注释和字符串字面量后按分号分割，
     *       若存在多条非空语句则拒绝。</li>
     *   <li>首 token 检测：每条语句的首个关键字不得为 DDL/DML。</li>
     * </ol>
     *
     * @return 违规原因字符串；无违规时返回 {@code null}
     */
    private String detectSqlViolation(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        // 去掉行注释（-- ...）和块注释（/* ... */）
        String stripped = LINE_COMMENT.matcher(sql).replaceAll(" ");
        stripped = BLOCK_COMMENT.matcher(stripped).replaceAll(" ").trim();

        // 去掉单引号字符串字面量（用占位符替换），避免字面量内的分号被误判为多语句分隔符
        // 例如 SELECT * FROM t WHERE name='hello; world' 不应被拒绝
        String noLiteral = STRING_LITERAL.matcher(stripped).replaceAll("''");

        // 按分号分割，过滤空段
        String[] segments = noLiteral.split(";");
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String seg : segments) {
            if (!seg.isBlank()) nonEmpty.add(seg.trim());
        }

        // 多语句拒绝
        if (nonEmpty.size() > 1) {
            return "querySql 不允许多语句（检测到 " + nonEmpty.size() + " 条语句），已拒绝执行。如需写操作请使用 execSql。";
        }

        // 逐条检测首 token（使用去字面量后的片段，避免引号内关键字误判）
        for (String seg : nonEmpty) {
            String[] tokens = seg.split("\\s+");
            if (tokens.length == 0) continue;
            String firstToken = tokens[0].toLowerCase();
            if (DDL_DML_KEYWORDS.contains(firstToken)) {
                return "querySql 仅允许只读查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH），检测到写入或结构变更语句（" + tokens[0] + "），已拒绝执行。如需写操作请使用 execSql。";
            }
        }
        return null;
    }

}
