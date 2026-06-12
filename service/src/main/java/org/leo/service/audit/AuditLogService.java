package org.leo.service.audit;

import org.leo.core.entity.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.leo.dao.mapper.AuditLogMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志服务类
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Service
public class AuditLogService {
    private final AuditLogMapper auditLogMapper;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd");
    private static final int RECENT_DAYS = 7;

    @Autowired
    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 插入审计日志
     */
    public boolean insertAuditLog(AuditLog auditLog) {
        if (auditLog == null) {
            return false;
        }
        if (auditLog.getLogId() == null || auditLog.getLogId().isBlank()) {
            auditLog.setLogId(UUID.randomUUID().toString());
        }
        if (auditLog.getCreateTime() == null || auditLog.getCreateTime().isBlank()) {
            auditLog.setCreateTime(DATE_FORMAT.format(new Date()));
        }
        if (auditLog.getStatus() == null || auditLog.getStatus().isBlank()) {
            auditLog.setStatus("SUCCESS");
        }
        return auditLogMapper.insertAuditLog(
            auditLog.getLogId(),
            auditLog.getUserId(),
            auditLog.getUserName(),
            auditLog.getPuppetId(),
            auditLog.getPuppetName(),
            auditLog.getSessionId(),
            auditLog.getOperationType(),
            auditLog.getOperationName(),
            auditLog.getOperationPath(),
            auditLog.getRequestParams(),
            auditLog.getResponseCode(),
            auditLog.getResponseMessage(),
            auditLog.getStatus(),
            auditLog.getErrorMessage(),
            auditLog.getClientIp(),
            auditLog.getCreateTime(),
            auditLog.getRemark()
        );
    }

    /**
     * 根据ID查询审计日志
     */
    public AuditLog findAuditLogById(String logId) {
        if (logId == null || logId.isBlank()) {
            return null;
        }
        return auditLogMapper.findAuditLogById(logId);
    }

    /**
     * 根据会话ID查询审计日志（按时间升序，适合生成操作报告）
     */
    public List<AuditLog> findAuditLogsBySessionId(String sessionId, Integer limit, Integer offset) {
        if (sessionId == null || sessionId.isBlank()) {
            return new java.util.ArrayList();
        }
        if (limit == null || limit <= 0) {
            limit = 500;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAuditLogsBySessionId(sessionId, limit, offset);
        return logs != null ? logs : new java.util.ArrayList();
    }

    /**
     * 统计会话的操作日志数量
     */
    public Integer countAuditLogsBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Integer count = auditLogMapper.countAuditLogsBySessionId(sessionId);
        return count != null ? count : 0;
    }

    /**
     * 根据用户ID查询审计日志
     */
    public List<AuditLog> findAuditLogsByUserId(String userId, Integer limit, Integer offset) {
        if (userId == null || userId.isBlank()) {
            return new java.util.ArrayList();
        }
        if (limit == null || limit <= 0) {
            limit = 100;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAuditLogsByUserId(userId, limit, offset);
        return logs != null ? logs : new java.util.ArrayList();
    }

    /**
     * 根据主机ID查询审计日志
     */
    public List<AuditLog> findAuditLogsByPuppetId(String puppetId, Integer limit, Integer offset) {
        if (puppetId == null || puppetId.isBlank()) {
            return new java.util.ArrayList();
        }
        if (limit == null || limit <= 0) {
            limit = 100;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAuditLogsByPuppetId(puppetId, limit, offset);
        return logs != null ? logs : new java.util.ArrayList();
    }

    /**
     * 根据操作类型查询审计日志
     */
    public List<AuditLog> findAuditLogsByOperationType(String operationType, Integer limit, Integer offset) {
        if (operationType == null || operationType.isBlank()) {
            return new java.util.ArrayList();
        }
        if (limit == null || limit <= 0) {
            limit = 100;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAuditLogsByOperationType(operationType, limit, offset);
        return logs != null ? logs : new java.util.ArrayList();
    }

    /**
     * 查询所有审计日志
     */
    public List<AuditLog> findAllAuditLogs(Integer limit, Integer offset) {
        if (limit == null || limit <= 0) {
            limit = 100;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAllAuditLogs(limit, offset);
        return logs != null ? logs : new java.util.ArrayList();
    }

    /**
     * 统计用户的操作日志数量
     */
    public Integer countAuditLogsByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        Integer count = auditLogMapper.countAuditLogsByUserId(userId);
        return count != null ? count : 0;
    }

    public Map<String, Object> getUserStatistics(String userId) {
        if (userId == null || userId.isBlank()) {
            return new HashMap<>();
        }
        String normalizedUserId = userId.trim();
        AuditLog latest = auditLogMapper.findLatestAuditLogByUserId(normalizedUserId);

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("userId", normalizedUserId);
        statistics.put("totalOperations", countAuditLogsByUserId(normalizedUserId));
        statistics.put("recentOperations", defaultCount(auditLogMapper.countRecentAuditLogsByUserId(normalizedUserId, RECENT_DAYS)));
        statistics.put("lastOperation", latest != null ? latest.getCreateTime() : null);
        return statistics;
    }

    /**
     * 统计主机的操作日志数量
     */
    public Integer countAuditLogsByPuppetId(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) {
            return 0;
        }
        Integer count = auditLogMapper.countAuditLogsByPuppetId(puppetId);
        return count != null ? count : 0;
    }

    public Map<String, Object> getPuppetStatistics(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) {
            return new HashMap<>();
        }
        String normalizedPuppetId = puppetId.trim();
        AuditLog latest = auditLogMapper.findLatestAuditLogByPuppetId(normalizedPuppetId);

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("hostId", normalizedPuppetId);
        statistics.put("puppetId", normalizedPuppetId);
        statistics.put("totalOperations", countAuditLogsByPuppetId(normalizedPuppetId));
        statistics.put("recentOperations", defaultCount(auditLogMapper.countRecentAuditLogsByPuppetId(normalizedPuppetId, RECENT_DAYS)));
        statistics.put("lastOperation", latest != null ? latest.getCreateTime() : null);
        return statistics;
    }

    public Map<String, Object> getTeamStatistics(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return new HashMap<>();
        }
        String normalizedTeamId = teamId.trim();
        AuditLog latest = auditLogMapper.findLatestAuditLogByTeamId(normalizedTeamId);

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("teamId", normalizedTeamId);
        statistics.put("totalOperations", defaultCount(auditLogMapper.countAuditLogsByTeamId(normalizedTeamId)));
        statistics.put("recentOperations", defaultCount(auditLogMapper.countRecentAuditLogsByTeamId(normalizedTeamId, RECENT_DAYS)));
        statistics.put("lastOperation", latest != null ? latest.getCreateTime() : null);
        return statistics;
    }

    public List<Map<String, Object>> getOperationStatistics() {
        List<Map<String, Object>> rows = auditLogMapper.countAuditLogsByOperationType();
        return rows != null ? rows : new ArrayList<>();
    }

    public List<Map<String, Object>> getTrendStatistics(Integer days) {
        int normalizedDays = normalizeTrendDays(days);
        List<Map<String, Object>> rows = auditLogMapper.countAuditLogsByDay(normalizedDays - 1);
        Map<String, Integer> countsByDay = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object day = row.get("day");
                Object count = row.get("count");
                if (day != null) {
                    countsByDay.put(day.toString(), toInt(count));
                }
            }
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(normalizedDays - 1L);
        for (int i = 0; i < normalizedDays; i++) {
            LocalDate day = start.plusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", DAY_LABEL_FORMAT.format(day));
            item.put("day", DAY_KEY_FORMAT.format(day));
            item.put("count", countsByDay.getOrDefault(DAY_KEY_FORMAT.format(day), 0));
            trend.add(item);
        }
        return trend;
    }

    /**
     * 统计所有日志数量
     */
    public Integer countAllAuditLogs() {
        Integer count = auditLogMapper.countAllAuditLogs();
        return count != null ? count : 0;
    }

    /**
     * 删除指定天数之前的旧日志
     */
    public Integer deleteOldAuditLogs(Integer days) {
        if (days == null || days <= 0) {
            days = 30; // 默认30天
        }
        Integer deleted = auditLogMapper.deleteOldAuditLogs(days);
        return deleted != null ? deleted : 0;
    }

    private int normalizeTrendDays(Integer days) {
        if (days == null || days <= 0) {
            return RECENT_DAYS;
        }
        return Math.min(days, 90);
    }

    private int defaultCount(Integer count) {
        return count != null ? count : 0;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
