package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogCounts;
import org.leo.core.entity.AuditLogListItem;
import org.leo.core.entity.AuditLogQuery;

import java.util.List;
import java.util.Map;


@Mapper
public interface AuditLogMapper {

    String AUDIT_LOG_FILTER_WHERE = """
            <where>
                <if test="query.userId != null and query.userId != ''">
                    AND user_id = #{query.userId}
                </if>
                <if test="query.userName != null and query.userName != ''">
                    AND user_name LIKE '%' || #{query.userName} || '%'
                </if>
                <if test="query.agentId != null and query.agentId != ''">
                    AND agent_id = #{query.agentId}
                </if>
                <if test="query.agentName != null and query.agentName != ''">
                    AND agent_name LIKE '%' || #{query.agentName} || '%'
                </if>
                <if test="query.sessionId != null and query.sessionId != ''">
                    AND session_id = #{query.sessionId}
                </if>
                <if test="query.operationType != null and query.operationType != ''">
                    AND operation_type = #{query.operationType}
                </if>
                <if test="query.status != null and query.status != ''">
                    AND status = #{query.status}
                </if>
                <if test="query.clientIp != null and query.clientIp != ''">
                    AND client_ip LIKE '%' || #{query.clientIp} || '%'
                </if>
                <if test="query.remark != null and query.remark != ''">
                    AND remark = #{query.remark}
                </if>
                <if test="query.startTime != null and query.startTime != ''">
                    AND create_time &gt;= #{query.startTime}
                </if>
                <if test="query.endTime != null and query.endTime != ''">
                    AND create_time &lt;= #{query.endTime}
                </if>
                <if test="query.keyword != null and query.keyword != ''">
                    AND (
                        operation_type LIKE '%' || #{query.keyword} || '%'
                        OR operation_name LIKE '%' || #{query.keyword} || '%'
                        OR operation_path LIKE '%' || #{query.keyword} || '%'
                        OR request_params LIKE '%' || #{query.keyword} || '%'
                        OR response_message LIKE '%' || #{query.keyword} || '%'
                        OR error_message LIKE '%' || #{query.keyword} || '%'
                        OR user_name LIKE '%' || #{query.keyword} || '%'
                        OR agent_name LIKE '%' || #{query.keyword} || '%'
                        OR client_ip LIKE '%' || #{query.keyword} || '%'
                    )
                </if>
            </where>
            """;

    String AUDIT_LOG_ORDER = "ORDER BY create_time DESC, log_id DESC";

    String AUDIT_STATISTICS_SELECT = """
            SELECT COUNT(*) AS total_operations,
                   COUNT(CASE WHEN create_time >= #{recentSince} THEN 1 END) AS recent_operations,
                   CAST(MAX(create_time) AS TEXT) AS last_operation
            FROM audit_logs
            """;

    @Insert("""
            INSERT INTO audit_logs (
                log_id, user_id, user_name, actor_team_id, agent_id,
                agent_name, session_id, operation_type, operation_name,
                operation_path, request_params, response_code, response_message,
                status, error_message, client_ip, create_time,
                remark
            ) VALUES (
                #{log.logId}, #{log.userId}, #{log.userName},
                (SELECT team_id FROM users WHERE user_id = #{log.userId}), #{log.agentId},
                #{log.agentName}, #{log.sessionId}, #{log.operationType}, #{log.operationName},
                #{log.operationPath}, #{log.requestParams}, #{log.responseCode}, #{log.responseMessage},
                #{log.status}, #{log.errorMessage}, #{log.clientIp}, #{log.createTime},
                #{log.remark}
            )
            """)
    boolean insertAuditLog(@Param("log") AuditLog log);

    @Select("SELECT * FROM audit_logs WHERE log_id = #{logId}")
    AuditLog findAuditLogById(@Param("logId") String logId);

    @Select({
            "<script>",
            """
            SELECT log_id, user_id, user_name, agent_id, agent_name, operation_type, operation_name,
                   operation_path, status, client_ip, create_time
            FROM audit_logs
            WHERE rowid IN (
                SELECT rowid FROM audit_logs
            """,
            AUDIT_LOG_FILTER_WHERE,
            AUDIT_LOG_ORDER,
            "LIMIT #{rowLimit} OFFSET #{query.offset}",
            ")",
            AUDIT_LOG_ORDER,
            "</script>"
    })
    List<AuditLogListItem> findAuditLogListItems(@Param("query") AuditLogQuery query, @Param("rowLimit") int rowLimit);

    @Select({
            "<script>",
            """
            SELECT COUNT(*) AS total,
            <choose>
                <when test="query.status != null and query.status != ''">
                    CASE WHEN #{query.status} = 'SUCCESS' THEN COUNT(*) ELSE 0 END AS success_count,
                    CASE WHEN #{query.status} IN ('FAILED', 'ERROR') THEN COUNT(*) ELSE 0 END AS failed_count
                </when>
                <otherwise>
                    COUNT(CASE WHEN status = 'SUCCESS' THEN 1 END) AS success_count,
                    COUNT(CASE WHEN status IN ('FAILED', 'ERROR') THEN 1 END) AS failed_count
                </otherwise>
            </choose>
            FROM audit_logs
            """,
            AUDIT_LOG_FILTER_WHERE,
            "</script>"
    })
    AuditLogCounts countAuditLogsByStatus(@Param("query") AuditLogQuery query);

    @Select(AUDIT_STATISTICS_SELECT + "WHERE user_id = #{userId}")
    SubjectStatistics summarizeUserAuditLogs(@Param("userId") String userId, @Param("recentSince") String recentSince);

    @Select(AUDIT_STATISTICS_SELECT + "WHERE agent_id = #{agentId}")
    SubjectStatistics summarizeAgentAuditLogs(@Param("agentId") String agentId, @Param("recentSince") String recentSince);

    @Select(AUDIT_STATISTICS_SELECT + "WHERE actor_team_id = #{teamId}")
    SubjectStatistics summarizeTeamAuditLogs(@Param("teamId") String teamId, @Param("recentSince") String recentSince);

    record SubjectStatistics(long totalOperations, long recentOperations, String lastOperation) {
    }

    @Select("""
            <script>
            SELECT operation_type AS operation, MIN(operation_name) AS operationName, COUNT(*) AS count
            FROM audit_logs
            <if test="startTime != null">
                WHERE create_time &gt;= #{startTime} AND create_time &lt; #{endExclusive}
            </if>
            GROUP BY operation_type ORDER BY count DESC, operation_type ASC
            </script>
            """)
    List<Map<String, Object>> countAuditLogsByOperationType(@Param("startTime") String startTime,
                                                            @Param("endExclusive") String endExclusive);

    @Select("""
            SELECT strftime('%Y-%m-%d', create_time) AS day, COUNT(*) AS count
            FROM audit_logs
            WHERE create_time >= #{startTime} AND create_time < #{endExclusive}
            GROUP BY day ORDER BY day ASC
            """)
    List<Map<String, Object>> countAuditLogsByDay(@Param("startTime") String startTime,
                                                  @Param("endExclusive") String endExclusive);

    @Delete("DELETE FROM audit_logs WHERE create_time < #{cutoff}")
    Integer deleteOldAuditLogs(@Param("cutoff") String cutoff);

    @Delete({
            "<script>",
            "DELETE FROM audit_logs WHERE log_id IN",
            "<foreach collection='logIds' item='logId' open='(' separator=',' close=')'>",
            "#{logId}",
            "</foreach>",
            "</script>"
    })
    Integer deleteAuditLogsByIds(@Param("logIds") List<String> logIds);

    @Delete({
            "<script>",
            "DELETE FROM audit_logs",
            AUDIT_LOG_FILTER_WHERE,
            "</script>"
    })
    Integer deleteAuditLogsByFilter(@Param("query") AuditLogQuery query);
}
