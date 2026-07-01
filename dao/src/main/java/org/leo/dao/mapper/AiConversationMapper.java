package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiThreadRecord;

import java.util.List;

@Mapper
public interface AiConversationMapper {

    @Select("SELECT * FROM ai_threads WHERE scope = #{scope} AND user_id = #{userId} AND puppet_id = #{puppetId} "
            + "AND parent_thread_id IS NULL ORDER BY last_active_at DESC")
    List<AiThreadRecord> listThreads(@Param("scope") String scope,
                                     @Param("userId") String userId,
                                     @Param("puppetId") String puppetId);

    @Select("SELECT * FROM ai_threads WHERE scope = 'platform' AND user_id = #{userId} "
            + "AND parent_thread_id IS NULL ORDER BY last_active_at DESC")
    List<AiThreadRecord> listPlatformThreads(@Param("userId") String userId);

    @Select("SELECT * FROM ai_threads WHERE parent_thread_id = #{parentThreadId} ORDER BY created_at ASC")
    List<AiThreadRecord> listChildThreads(@Param("parentThreadId") String parentThreadId);

    @Select("SELECT * FROM ai_threads WHERE thread_id = #{threadId}")
    AiThreadRecord findThread(@Param("threadId") String threadId);

    @Insert("INSERT INTO ai_threads (thread_id, scope, user_id, puppet_id, session_id, title, config_id, "
            + "config_name, config_protocol, config_model, config_base_url, config_completions_path, "
            + "config_max_output_tokens, created_at, last_active_at, message_count, run_status, "
            + "parent_thread_id, profile, mode, context_summary, root_plan_id) "
            + "VALUES (#{threadId}, #{scope}, #{userId}, #{puppetId}, #{sessionId}, #{title}, #{configId}, "
            + "#{configName}, #{configProtocol}, #{configModel}, #{configBaseUrl}, #{configCompletionsPath}, "
            + "#{configMaxOutputTokens}, #{createdAt}, #{lastActiveAt}, #{messageCount}, #{runStatus}, "
            + "#{parentThreadId}, 'default', COALESCE(#{mode}, 'auto'), "
            + "#{contextSummary}, #{rootPlanId})")
    int insertThread(AiThreadRecord row);

    @Update("UPDATE ai_threads SET title = #{title}, last_active_at = #{lastActiveAt} WHERE thread_id = #{threadId}")
    int renameThread(@Param("threadId") String threadId,
                     @Param("title") String title,
                     @Param("lastActiveAt") long lastActiveAt);

    @Update("UPDATE ai_threads SET session_id = #{sessionId}, last_active_at = #{lastActiveAt}, "
            + "run_status = #{runStatus} WHERE thread_id = #{threadId}")
    int updateThreadRuntime(AiThreadRecord row);

    @Update("UPDATE ai_threads SET config_id = #{configId}, config_name = #{configName}, "
            + "config_protocol = #{configProtocol}, config_model = #{configModel}, "
            + "config_base_url = #{configBaseUrl}, config_completions_path = #{configCompletionsPath}, "
            + "config_max_output_tokens = #{configMaxOutputTokens}, last_active_at = #{lastActiveAt} "
            + "WHERE thread_id = #{threadId}")
    int updateThreadConfig(AiThreadRecord row);

    @Update("UPDATE ai_threads SET mode = #{mode}, last_active_at = #{lastActiveAt} "
            + "WHERE thread_id = #{threadId}")
    int updateThreadMode(@Param("threadId") String threadId,
                         @Param("mode") String mode,
                         @Param("lastActiveAt") long lastActiveAt);

    @Update("UPDATE ai_threads SET context_summary = #{contextSummary}, last_active_at = #{lastActiveAt} "
            + "WHERE thread_id = #{threadId}")
    int updateThreadContextSummary(@Param("threadId") String threadId,
                                   @Param("contextSummary") String contextSummary,
                                   @Param("lastActiveAt") long lastActiveAt);

    @Update("UPDATE ai_threads SET message_count = (SELECT COUNT(*) FROM ai_messages WHERE thread_id = #{threadId}), "
            + "last_active_at = #{lastActiveAt} WHERE thread_id = #{threadId}")
    int refreshMessageCount(@Param("threadId") String threadId, @Param("lastActiveAt") long lastActiveAt);

    @Delete("DELETE FROM ai_threads WHERE thread_id = #{threadId}")
    int deleteThread(@Param("threadId") String threadId);

    @Delete("DELETE FROM ai_messages WHERE thread_id = #{threadId}")
    int deleteMessages(@Param("threadId") String threadId);

    @Delete("DELETE FROM ai_runs WHERE thread_id = #{threadId}")
    int deleteRuns(@Param("threadId") String threadId);

    @Delete("DELETE FROM ai_subagent_invocations WHERE parent_thread_id = #{threadId} OR child_thread_id = #{threadId}")
    int deleteSubagentInvocations(@Param("threadId") String threadId);

    @Insert("INSERT INTO ai_messages (message_id, thread_id, role, content, timestamp, "
            + "nodes_json, review_json, plan_json) "
            + "VALUES (#{messageId}, #{threadId}, #{role}, #{content}, #{timestamp}, "
            + "#{nodesJson}, #{reviewJson}, #{planJson})")
    int insertMessage(AiMessageRecord row);

    @Select("SELECT * FROM ai_messages WHERE thread_id = #{threadId} ORDER BY timestamp ASC, message_id ASC "
            + "LIMIT #{limit} OFFSET #{offset}")
    List<AiMessageRecord> listMessages(@Param("threadId") String threadId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_messages WHERE thread_id = #{threadId}")
    int countMessages(@Param("threadId") String threadId);

    @Select("SELECT * FROM (SELECT * FROM ai_messages WHERE thread_id = #{threadId} "
            + "ORDER BY timestamp DESC, message_id DESC LIMIT #{limit}) ORDER BY timestamp ASC, message_id ASC")
    List<AiMessageRecord> recentMessages(@Param("threadId") String threadId, @Param("limit") int limit);

    @Insert("INSERT INTO ai_runs (run_id, thread_id, status, started_at, finished_at, duration_ms, "
            + "config_id, input, output, error_message, tool_call_count, runtime_json) "
            + "VALUES (#{runId}, #{threadId}, #{status}, #{startedAt}, #{finishedAt}, #{durationMs}, "
            + "#{configId}, #{input}, #{output}, #{errorMessage}, #{toolCallCount}, #{runtimeJson})")
    int insertRun(AiRunRecord row);

    @Update("UPDATE ai_runs SET status = #{status}, finished_at = #{finishedAt}, duration_ms = #{durationMs}, "
            + "output = #{output}, error_message = #{errorMessage}, tool_call_count = #{toolCallCount} "
            + "WHERE run_id = #{runId}")
    int finishRun(AiRunRecord row);

    // ── 子 Agent 调用记录 ─────────────────────────────────────────────────────
    @Insert("INSERT INTO ai_subagent_invocations (invocation_id, parent_thread_id, parent_message_id, "
            + "child_thread_id, profile, task, input_json, summary, status, created_at, completed_at) "
            + "VALUES (#{invocationId}, #{parentThreadId}, #{parentMessageId}, #{childThreadId}, "
            + "'default', #{task}, #{inputJson}, #{summary}, #{status}, #{createdAt}, #{completedAt})")
    int insertSubagentInvocation(AiSubagentInvocation row);

    @Update("UPDATE ai_subagent_invocations SET child_thread_id = #{childThreadId}, status = #{status}, "
            + "summary = #{summary}, completed_at = #{completedAt} WHERE invocation_id = #{invocationId}")
    int updateSubagentInvocation(AiSubagentInvocation row);

    @Select("SELECT * FROM ai_subagent_invocations WHERE parent_thread_id = #{parentThreadId} "
            + "ORDER BY created_at ASC")
    List<AiSubagentInvocation> listSubagentInvocations(@Param("parentThreadId") String parentThreadId);
}
