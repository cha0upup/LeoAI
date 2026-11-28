package org.leo.service.audit;

import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogCounts;
import org.leo.core.entity.AuditLogListItem;
import org.leo.core.entity.AuditLogQuery;
import org.leo.dao.mapper.AuditLogMapper;
import org.leo.dao.mapper.AuditLogMapper.SubjectStatistics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

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

    /** 同一次读取中返回分页和筛选后的全量计数，避免并发写入让两者使用不同快照。 */
    @Transactional(readOnly = true)
    public AuditLogSummaryPage getSummaryPage(AuditLogQuery query) {
        AuditLogQuery normalized = normalizeQuery(query);
        int limit = normalized.getLimit();
        int offset = normalized.getOffset();
        // 首页多读一条摘要，区分恰好一页和仍有下一页。
        int rowLimit = offset == 0 ? limit + 1 : limit;
        List<AuditLogListItem> rows = auditLogMapper.findAuditLogListItems(normalized, rowLimit);
        AuditLogCounts counts;
        if (offset == 0 && rows.size() <= limit) {
            counts = countCompletePage(rows);
        } else {
            counts = auditLogMapper.countAuditLogsByStatus(normalized);
            if (rows.size() > limit) rows = rows.subList(0, limit);
        }
        return new AuditLogSummaryPage(rows, counts.total(), counts.successCount(), counts.failedCount(), limit, offset);
    }

    private static AuditLogCounts countCompletePage(List<AuditLogListItem> rows) {
        long success = 0;
        long failed = 0;
        for (AuditLogListItem row : rows) {
            String status = row.status();
            if ("SUCCESS".equals(status)) success++;
            else if ("FAILED".equals(status) || "ERROR".equals(status)) failed++;
        }
        return new AuditLogCounts(rows.size(), success, failed);
    }

    public record AuditLogSummaryPage(List<AuditLogListItem> logs, long total, long successCount, long failedCount,
                                      int limit, int offset) {
    }

    public Map<String, Object> getUserStatistics(String userId) {
        return getSubjectStatistics("userId", userId, auditLogMapper::summarizeUserAuditLogs);
    }

    public Map<String, Object> getAgentStatistics(String agentId) {
        return getSubjectStatistics("agentId", agentId, auditLogMapper::summarizeAgentAuditLogs);
    }

    public Map<String, Object> getTeamStatistics(String teamId) {
        return getSubjectStatistics("teamId", teamId, auditLogMapper::summarizeTeamAuditLogs);
    }

    private Map<String, Object> getSubjectStatistics(String idField, String id,
                                                    BiFunction<String, String, SubjectStatistics> query) {
        Map<String, Object> statistics = new HashMap<>();
        if (!hasText(id)) return statistics;
        String normalizedId = id.trim();
        SubjectStatistics result = query.apply(normalizedId, cutoffDaysAgo(RECENT_DAYS));
        statistics.put("totalOperations", (int) result.totalOperations());
        statistics.put("recentOperations", (int) result.recentOperations());
        statistics.put("lastOperation", result.lastOperation());
        statistics.put(idField, normalizedId);
        return statistics;
    }

    private static String cutoffDaysAgo(int days) {
        // Audit timestamps are written in the application's local time, not SQLite's UTC time.
        return DATE_TIME_FORMAT.format(LocalDateTime.now().minusDays(days));
    }

    public List<Map<String, Object>> getOperationStatistics(Integer days) {
        List<Map<String, Object>> rows;
        if (days == null) {
            rows = auditLogMapper.countAuditLogsByOperationType(null, null);
        } else {
            LocalDate today = LocalDate.now();
            LocalDate start = today.minusDays(normalizeStatisticsDays(days) - 1L);
            rows = auditLogMapper.countAuditLogsByOperationType(startOfDay(start), startOfDay(today.plusDays(1)));
        }
        return rows != null ? rows : List.of();
    }

    public List<Map<String, Object>> getTrendStatistics(Integer days) {
        int normalizedDays = normalizeStatisticsDays(days);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(normalizedDays - 1L);
        List<Map<String, Object>> rows = auditLogMapper.countAuditLogsByDay(
                startOfDay(start), startOfDay(today.plusDays(1)));
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

        List<Map<String, Object>> trend = new ArrayList<>(normalizedDays);
        for (int i = 0; i < normalizedDays; i++) {
            LocalDate day = start.plusDays(i);
            String dayKey = DAY_KEY_FORMAT.format(day);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", DAY_LABEL_FORMAT.format(day));
            item.put("day", dayKey);
            item.put("count", countsByDay.getOrDefault(dayKey, 0));
            trend.add(item);
        }
        return trend;
    }

    /**
     * 删除指定天数之前的旧日志
     */
    public Integer deleteOldAuditLogs(Integer days) {
        int retentionDays = days == null || days <= 0 ? 30 : days;
        return defaultCount(auditLogMapper.deleteOldAuditLogs(cutoffDaysAgo(retentionDays)));
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

    /** 返回原子删除语句实际删除的日志数。 */
    public int deleteAuditLogsByFilter(AuditLogQuery query) {
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

    private static String startOfDay(LocalDate day) {
        return day.atStartOfDay().format(DATE_TIME_FORMAT);
    }

    private int normalizeStatisticsDays(Integer days) {
        if (days == null || days <= 0) {
            return RECENT_DAYS;
        }
        return Math.min(days, 90);
    }

    private AuditLogQuery normalizeQuery(AuditLogQuery query) {
        return query == null ? AuditLogQuery.page(null, null) : query.normalized();
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
