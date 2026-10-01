package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system and application logs.
 */
public interface EventLogCapable {

    Map<String, Object> listEventLogSources() throws Exception;

    Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                      String level, String since, String until,
                                      String eventId) throws Exception;

    Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                      String level, String since, String until,
                                      String eventId, String format) throws Exception;

    Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                      String level, String since, String until,
                                      String eventId, String format, int maxBytes) throws Exception;

    Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                      String level, String since, String until,
                                      String eventId, String format, int maxBytes,
                                      Long cursor, String direction,
                                      Integer minStatus, Integer maxStatus,
                                      String ipPrefix, String pathPrefix) throws Exception;

    Map<String, Object> getEventLogStats(String source) throws Exception;

    Map<String, Object> clearEventLog(String source) throws Exception;

    Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                          int topN, int maxScan, String keyword,
                                          Integer minStatus, Integer maxStatus,
                                          String ipPrefix, String pathPrefix) throws Exception;

    Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                          int topN, int maxScan, int maxBytes, String keyword,
                                          Integer minStatus, Integer maxStatus,
                                          String ipPrefix, String pathPrefix, boolean slow) throws Exception;

    Map<String, Object> previewEventLog(String source, int lines, boolean fromTail) throws Exception;

    Map<String, Object> metaEventLog(String source, String format) throws Exception;

    Map<String, Object> metaEventLog(String source, String format, int lines, boolean fromTail) throws Exception;
}
