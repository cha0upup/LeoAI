package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leo.core.entity.AuditLog;

import java.util.List;
import java.util.Map;


@Mapper
public interface AuditLogMapper {

    @Insert("INSERT INTO audit_logs (log_id, user_id, user_name, puppet_id, puppet_name, session_id, operation_type, operation_name, operation_path, request_params, response_code, response_message, status, error_message, client_ip, create_time, remark) VALUES (#{logId}, #{userId}, #{userName}, #{puppetId}, #{puppetName}, #{sessionId}, #{operationType}, #{operationName}, #{operationPath}, #{requestParams}, #{responseCode}, #{responseMessage}, #{status}, #{errorMessage}, #{clientIp}, #{createTime}, #{remark})")
    boolean insertAuditLog(@Param("logId") String logId,
                          @Param("userId") String userId,
                          @Param("userName") String userName,
                          @Param("puppetId") String puppetId,
                          @Param("puppetName") String puppetName,
                          @Param("sessionId") String sessionId,
                          @Param("operationType") String operationType,
                          @Param("operationName") String operationName,
                          @Param("operationPath") String operationPath,
                          @Param("requestParams") String requestParams,
                          @Param("responseCode") Integer responseCode,
                          @Param("responseMessage") String responseMessage,
                          @Param("status") String status,
                          @Param("errorMessage") String errorMessage,
                          @Param("clientIp") String clientIp,
                          @Param("createTime") String createTime,
                          @Param("remark") String remark);

    @Select("SELECT * FROM audit_logs WHERE log_id = #{logId}")
    AuditLog findAuditLogById(@Param("logId") String logId);

    @Select("SELECT * FROM audit_logs WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByUserId(@Param("userId") String userId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs WHERE puppet_id = #{puppetId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByPuppetId(@Param("puppetId") String puppetId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs WHERE operation_type = #{operationType} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByOperationType(@Param("operationType") String operationType, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAllAuditLogs(@Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE user_id = #{userId}")
    Integer countAuditLogsByUserId(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE user_id = #{userId} AND create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByUserId(@Param("userId") String userId, @Param("days") Integer days);

    @Select("SELECT * FROM audit_logs WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM audit_logs WHERE session_id = #{sessionId} ORDER BY create_time ASC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsBySessionId(@Param("sessionId") String sessionId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE session_id = #{sessionId}")
    Integer countAuditLogsBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE puppet_id = #{puppetId}")
    Integer countAuditLogsByPuppetId(@Param("puppetId") String puppetId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE puppet_id = #{puppetId} AND create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByPuppetId(@Param("puppetId") String puppetId, @Param("days") Integer days);

    @Select("SELECT * FROM audit_logs WHERE puppet_id = #{puppetId} ORDER BY create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByPuppetId(@Param("puppetId") String puppetId);

    @Select("SELECT COUNT(*) FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId}")
    Integer countAuditLogsByTeamId(@Param("teamId") String teamId);

    @Select("SELECT COUNT(*) FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId} AND a.create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByTeamId(@Param("teamId") String teamId, @Param("days") Integer days);

    @Select("SELECT a.* FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId} ORDER BY a.create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByTeamId(@Param("teamId") String teamId);

    @Select("SELECT COUNT(*) FROM audit_logs")
    Integer countAllAuditLogs();

    @Select("SELECT operation_type AS operation, MIN(operation_name) AS operationName, COUNT(*) AS count FROM audit_logs GROUP BY operation_type ORDER BY count DESC")
    List<Map<String, Object>> countAuditLogsByOperationType();

    @Select("SELECT strftime('%Y-%m-%d', create_time) AS day, COUNT(*) AS count FROM audit_logs WHERE create_time >= datetime('now', '-' || #{days} || ' days') GROUP BY day ORDER BY day ASC")
    List<Map<String, Object>> countAuditLogsByDay(@Param("days") Integer days);

    @Delete("DELETE FROM audit_logs WHERE create_time < datetime('now', '-' || #{days} || ' days')")
    Integer deleteOldAuditLogs(@Param("days") Integer days);
}
