package org.leo.service.audit;

import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.springframework.stereotype.Service;

import org.leo.dao.mapper.AuditLogMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd");
    private static final int RECENT_DAYS = 7;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;

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
        if (!hasText(auditLog.getLogId())) {
            auditLog.setLogId(UUID.randomUUID().toString());
        }
        if (!hasText(auditLog.getCreateTime())) {
            auditLog.setCreateTime(DATE_TIME_FORMAT.format(LocalDateTime.now()));
        }
        if (!hasText(auditLog.getStatus())) {
            auditLog.setStatus("SUCCESS");
        }
        return auditLogMapper.insertAuditLog(auditLog);
    }

    /**
     * 根据ID查询审计日志
     */
    public AuditLog findAuditLogById(String logId) {
        if (!hasText(logId)) {
            return null;
        }
        return auditLogMapper.findAuditLogById(logId);
    }

    /**
     * 根据会话ID查询审计日志（按时间升序，适合生成操作报告）
     */
    public List<AuditLog> findAuditLogsBySessionId(String sessionId, Integer limit, Integer offset) {
        if (!hasText(sessionId)) {
            return new ArrayList<>();
        }
        if (limit == null || limit <= 0) {
            limit = 500;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAuditLogsBySessionId(sessionId, limit, offset);
        return logs != null ? logs : new ArrayList<>();
    }

    /**
     * 统计会话的操作日志数量
     */
    public Integer countAuditLogsBySessionId(String sessionId) {
        if (!hasText(sessionId)) {
            return 0;
        }
        return defaultCount(auditLogMapper.countAuditLogsBySessionId(sessionId));
    }

    /**
     * 查询所有审计日志
     */
    public List<AuditLog> findAllAuditLogs(Integer limit, Integer offset) {
        if (limit == null || limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (offset == null || offset < 0) {
            offset = 0;
        }
        List<AuditLog> logs = auditLogMapper.findAllAuditLogs(limit, offset);
        return logs != null ? logs : new ArrayList<>();
    }

    public List<AuditLog> searchAuditLogs(AuditLogQuery query) {
        AuditLogQuery normalizedQuery = normalizeQuery(query);
        List<AuditLog> logs = auditLogMapper.searchAuditLogs(normalizedQuery);
        return logs != null ? logs : new ArrayList<>();
    }

    public Integer countAuditLogs(AuditLogQuery query) {
        return defaultCount(auditLogMapper.countAuditLogs(normalizeQuery(query)));
    }

    /**
     * 统计用户的操作日志数量
     */
    public Integer countAuditLogsByUserId(String userId) {
        if (!hasText(userId)) {
            return 0;
        }
        return defaultCount(auditLogMapper.countAuditLogsByUserId(userId));
    }

    public Map<String, Object> getUserStatistics(String userId) {
        if (!hasText(userId)) {
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
    public Integer countAuditLogsByAgentId(String agentId) {
        if (!hasText(agentId)) {
            return 0;
        }
        return defaultCount(auditLogMapper.countAuditLogsByAgentId(agentId));
    }

    public Map<String, Object> getAgentStatistics(String agentId) {
        if (!hasText(agentId)) {
            return new HashMap<>();
        }
        String normalizedAgentId = agentId.trim();
        AuditLog latest = auditLogMapper.findLatestAuditLogByAgentId(normalizedAgentId);

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("hostId", normalizedAgentId);
        statistics.put("agentId", normalizedAgentId);
        statistics.put("totalOperations", countAuditLogsByAgentId(normalizedAgentId));
        statistics.put("recentOperations", defaultCount(auditLogMapper.countRecentAuditLogsByAgentId(normalizedAgentId, RECENT_DAYS)));
        statistics.put("lastOperation", latest != null ? latest.getCreateTime() : null);
        return statistics;
    }

    public Map<String, Object> getTeamStatistics(String teamId) {
        if (!hasText(teamId)) {
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
        return defaultCount(auditLogMapper.countAllAuditLogs());
    }

    /**
     * 删除指定天数之前的旧日志
     */
    public Integer deleteOldAuditLogs(Integer days) {
        if (days == null || days <= 0) {
            days = 30; // 默认30天
        }
        return defaultCount(auditLogMapper.deleteOldAuditLogs(days));
    }

    public Integer deleteAuditLogsByIds(List<String> logIds) {
        if (logIds == null || logIds.isEmpty()) {
            return 0;
        }
        List<String> normalizedIds = logIds.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return 0;
        }
        return defaultCount(auditLogMapper.deleteAuditLogsByIds(normalizedIds));
    }

    public Integer deleteAuditLogsByFilter(AuditLogQuery query) {
        AuditLogQuery normalizedQuery = normalizeQuery(query);
        if (!hasDeleteFilter(normalizedQuery)) {
            throw new IllegalArgumentException("按筛选条件删除至少需要一个筛选条件");
        }
        return defaultCount(auditLogMapper.deleteAuditLogsByFilter(normalizedQuery));
    }

    public boolean hasDeleteFilter(AuditLogQuery query) {
        if (query == null) {
            return false;
        }
        return hasText(query.getUserId())
                || hasText(query.getUserName())
                || hasText(query.getAgentId())
                || hasText(query.getAgentName())
                || hasText(query.getSessionId())
                || hasText(query.getOperationType())
                || hasText(query.getStatus())
                || hasText(query.getClientIp())
                || hasText(query.getKeyword())
                || hasText(query.getRemark())
                || hasText(query.getStartTime())
                || hasText(query.getEndTime());
    }

    private int normalizeTrendDays(Integer days) {
        if (days == null || days <= 0) {
            return RECENT_DAYS;
        }
        return Math.min(days, 90);
    }

    private AuditLogQuery normalizeQuery(AuditLogQuery query) {
        AuditLogQuery normalizedQuery = query != null ? query : new AuditLogQuery();
        normalizedQuery.setUserId(trimToNull(normalizedQuery.getUserId()));
        normalizedQuery.setUserName(trimToNull(normalizedQuery.getUserName()));
        normalizedQuery.setAgentId(trimToNull(normalizedQuery.getAgentId()));
        normalizedQuery.setAgentName(trimToNull(normalizedQuery.getAgentName()));
        normalizedQuery.setSessionId(trimToNull(normalizedQuery.getSessionId()));
        normalizedQuery.setOperationType(trimToNull(normalizedQuery.getOperationType()));
        normalizedQuery.setStatus(trimToNull(normalizedQuery.getStatus()));
        normalizedQuery.setClientIp(trimToNull(normalizedQuery.getClientIp()));
        normalizedQuery.setKeyword(trimToNull(normalizedQuery.getKeyword()));
        normalizedQuery.setRemark(trimToNull(normalizedQuery.getRemark()));
        normalizedQuery.setStartTime(trimToNull(normalizedQuery.getStartTime()));
        normalizedQuery.setEndTime(trimToNull(normalizedQuery.getEndTime()));
        Integer limit = normalizedQuery.getLimit();
        if (limit == null || limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        normalizedQuery.setLimit(Math.min(limit, MAX_LIMIT));
        Integer offset = normalizedQuery.getOffset();
        normalizedQuery.setOffset(offset == null || offset < 0 ? 0 : offset);
        return normalizedQuery;
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
