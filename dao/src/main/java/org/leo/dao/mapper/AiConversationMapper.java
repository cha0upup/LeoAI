package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.leo.core.entity.AiOrphanedRunRecord;
import org.leo.core.entity.AiOperationAssessment;

import java.util.List;

@Mapper
public interface AiConversationMapper {

    @Insert("INSERT INTO ai_operation_assessments (assessment_id, user_id, thread_id, tool_name, arguments_json, arguments_hash, "
            + "risk_level, requires_confirmation, reason, impact, rollback, status, created_at, expires_at, consumed_at) "
            + "VALUES (#{assessmentId}, #{userId}, #{threadId}, #{toolName}, #{argumentsJson}, #{argumentsHash}, #{riskLevel}, "
            + "#{requiresConfirmation}, #{reason}, #{impact}, #{rollback}, #{status}, #{createdAt}, #{expiresAt}, #{consumedAt})")
    int insertOperationAssessment(AiOperationAssessment row);

    @Select("SELECT * FROM ai_operation_assessments WHERE user_id = #{userId} AND thread_id = #{threadId} "
            + "AND tool_name = #{toolName} AND arguments_hash = #{argumentsHash} AND status = 'PENDING' "
            + "AND (expires_at IS NULL OR expires_at > #{now}) ORDER BY created_at DESC LIMIT 1")
    AiOperationAssessment findPendingOperationAssessment(@Param("userId") String userId,
                                                          @Param("threadId") String threadId,
                                                          @Param("toolName") String toolName,
                                                          @Param("argumentsHash") String argumentsHash,
                                                          @Param("now") long now);

    @Update("UPDATE ai_operation_assessments SET status = 'EXPIRED' WHERE status = 'PENDING' "
            + "AND expires_at IS NOT NULL AND expires_at <= #{now}")
    int expireOperationAssessments(@Param("now") long now);

    @Update("UPDATE ai_operation_assessments SET status = 'CONSUMED', consumed_at = #{consumedAt} "
            + "WHERE assessment_id = #{assessmentId} AND status = 'PENDING' "
            + "AND (expires_at IS NULL OR expires_at > #{consumedAt})")
    int consumeOperationAssessment(@Param("assessmentId") String assessmentId,
                                   @Param("consumedAt") long consumedAt);

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
            + "parent_thread_id, profile, context_summary, context_checkpoint_json, root_plan_id) "
            + "VALUES (#{threadId}, #{scope}, #{userId}, #{puppetId}, #{sessionId}, #{title}, #{configId}, "
            + "#{configName}, #{configProtocol}, #{configModel}, #{configBaseUrl}, #{configCompletionsPath}, "
            + "#{configMaxOutputTokens}, #{createdAt}, #{lastActiveAt}, #{messageCount}, #{runStatus}, "
            + "#{parentThreadId}, 'default', "
            + "#{contextSummary}, #{contextCheckpointJson}, #{rootPlanId})")
    int insertThread(AiThreadRecord row);

    @Update("UPDATE ai_threads SET title = #{title}, last_active_at = #{lastActiveAt} WHERE thread_id = #{threadId}")
    int renameThread(@Param("threadId") String threadId,
                     @Param("title") String title,
                     @Param("lastActiveAt") long lastActiveAt);

    @Update("UPDATE ai_threads SET session_id = #{sessionId}, last_active_at = #{lastActiveAt}, "
            + "run_status = #{runStatus} WHERE thread_id = #{threadId}")
    int updateThreadRuntime(AiThreadRecord row);

    @Update("UPDATE ai_threads SET session_id = #{sessionId}, last_active_at = #{lastActiveAt}, "
            + "run_status = #{runStatus} WHERE thread_id = #{threadId} "
            + "AND EXISTS (SELECT 1 FROM ai_thread_leases l "
            + "WHERE l.thread_id = ai_threads.thread_id AND l.lease_token = #{leaseToken} "
            + "AND l.expires_at > #{writeAt})")
    int updateThreadRuntimeFenced(@Param("threadId") String threadId,
                                  @Param("sessionId") String sessionId,
                                  @Param("lastActiveAt") long lastActiveAt,
                                  @Param("runStatus") String runStatus,
                                  @Param("leaseToken") String leaseToken,
                                  @Param("writeAt") long writeAt);

    @Update("UPDATE ai_threads SET config_id = #{configId}, config_name = #{configName}, "
            + "config_protocol = #{configProtocol}, config_model = #{configModel}, "
            + "config_base_url = #{configBaseUrl}, config_completions_path = #{configCompletionsPath}, "
            + "config_max_output_tokens = #{configMaxOutputTokens}, last_active_at = #{lastActiveAt} "
            + "WHERE thread_id = #{threadId}")
    int updateThreadConfig(AiThreadRecord row);

    @Update("UPDATE ai_threads SET context_summary = #{contextSummary}, "
            + "context_checkpoint_json = #{contextCheckpointJson}, last_active_at = #{lastActiveAt} "
            + "WHERE thread_id = #{threadId}")
    int updateThreadContextCheckpoint(@Param("threadId") String threadId,
                                      @Param("contextSummary") String contextSummary,
                                      @Param("contextCheckpointJson") String contextCheckpointJson,
                                      @Param("lastActiveAt") long lastActiveAt);

    @Update("UPDATE ai_threads SET message_count = (SELECT COUNT(*) FROM ai_messages WHERE thread_id = #{threadId}), "
            + "last_active_at = #{lastActiveAt} WHERE thread_id = #{threadId}")
    int refreshMessageCount(@Param("threadId") String threadId, @Param("lastActiveAt") long lastActiveAt);

    @Delete("DELETE FROM ai_threads WHERE thread_id = #{threadId}")
    int deleteThread(@Param("threadId") String threadId);

    @Insert("INSERT OR IGNORE INTO ai_turns "
            + "(turn_id, thread_id, status, created_at, completed_at, "
            + "protocol_status, dispatch_status) "
            + "VALUES (#{turnId}, #{threadId}, #{status}, #{createdAt}, "
            + "#{completedAt}, 'completed', 'completed')")
    int insertTurn(AiTurnRecord row);

    @Insert("INSERT OR IGNORE INTO ai_turns "
            + "(turn_id, thread_id, status, created_at, protocol_status, "
            + "dispatch_status, command_scope, command_json, "
            + "client_user_message_id, user_item_id, assistant_item_id, "
            + "interrupt_requested, answer_to_question_id) VALUES "
            + "(#{turnId}, #{threadId}, 'pending', #{createdAt}, #{protocolStatus}, "
            + "'queued', #{commandScope}, #{commandJson}, "
            + "#{clientUserMessageId}, #{userItemId}, #{assistantItemId}, 0, "
            + "#{answerToQuestionId})")
    int insertProtocolTurn(AiTurnRecord row);

    @Select("SELECT * FROM ai_turns WHERE thread_id = #{threadId} "
            + "AND client_user_message_id = #{clientUserMessageId}")
    AiTurnRecord findTurnByClientMessage(
            @Param("threadId") String threadId,
            @Param("clientUserMessageId") String clientUserMessageId);

    @Select("SELECT * FROM ai_turns WHERE turn_id = #{turnId}")
    AiTurnRecord findTurnById(@Param("turnId") String turnId);

    @Select("SELECT * FROM ai_turns WHERE thread_id = #{threadId} "
            + "AND protocol_status = 'inProgress' AND dispatch_status = 'queued' "
            + "AND (NOT EXISTS (SELECT 1 FROM ai_user_input_requests input "
            + "WHERE input.thread_id = ai_turns.thread_id AND input.status = 'PENDING') "
            + "OR answer_to_question_id = (SELECT input.request_id "
            + "FROM ai_user_input_requests input WHERE input.thread_id = ai_turns.thread_id "
            + "AND input.status = 'PENDING' ORDER BY input.created_at DESC LIMIT 1)) "
            + "ORDER BY CASE WHEN answer_to_question_id IS NOT NULL THEN 0 ELSE 1 END, "
            + "created_at, turn_id LIMIT 1")
    AiTurnRecord findNextQueuedTurn(@Param("threadId") String threadId);

    @Select("SELECT * FROM ai_turns WHERE thread_id = #{threadId} "
            + "AND protocol_status = 'inProgress' "
            + "ORDER BY CASE dispatch_status "
            + "WHEN 'cancelling' THEN 0 WHEN 'running' THEN 1 ELSE 2 END, "
            + "created_at, turn_id")
    List<AiTurnRecord> listInProgressTurns(@Param("threadId") String threadId);

    @Select("SELECT DISTINCT queued.thread_id FROM ai_turns queued "
            + "WHERE queued.protocol_status = 'inProgress' "
            + "AND queued.dispatch_status = 'queued' "
            + "AND (NOT EXISTS (SELECT 1 FROM ai_user_input_requests input "
            + "WHERE input.thread_id = queued.thread_id AND input.status = 'PENDING') "
            + "OR queued.answer_to_question_id = (SELECT input.request_id "
            + "FROM ai_user_input_requests input WHERE input.thread_id = queued.thread_id "
            + "AND input.status = 'PENDING' ORDER BY input.created_at DESC LIMIT 1)) "
            + "AND NOT EXISTS (SELECT 1 FROM ai_turns active "
            + "WHERE active.thread_id = queued.thread_id "
            + "AND active.dispatch_status IN ('running', 'cancelling'))")
    List<String> listDispatchableThreadIds();

    @Update("UPDATE ai_turns SET dispatch_status = 'running', "
            + "started_at = COALESCE(started_at, #{startedAt}) "
            + "WHERE turn_id = #{turnId} AND protocol_status = 'inProgress' "
            + "AND dispatch_status = 'queued' "
            + "AND turn_id = (SELECT queued.turn_id FROM ai_turns queued "
            + "WHERE queued.thread_id = ai_turns.thread_id "
            + "AND queued.protocol_status = 'inProgress' "
            + "AND queued.dispatch_status = 'queued' "
            + "AND (NOT EXISTS (SELECT 1 FROM ai_user_input_requests input "
            + "WHERE input.thread_id = queued.thread_id AND input.status = 'PENDING') "
            + "OR queued.answer_to_question_id = (SELECT input.request_id "
            + "FROM ai_user_input_requests input WHERE input.thread_id = queued.thread_id "
            + "AND input.status = 'PENDING' ORDER BY input.created_at DESC LIMIT 1)) "
            + "ORDER BY CASE WHEN queued.answer_to_question_id IS NOT NULL THEN 0 ELSE 1 END, "
            + "queued.created_at, queued.turn_id LIMIT 1) "
            + "AND NOT EXISTS (SELECT 1 FROM ai_turns active "
            + "WHERE active.thread_id = ai_turns.thread_id "
            + "AND active.dispatch_status IN ('running', 'cancelling'))")
    int markProtocolTurnStarted(@Param("turnId") String turnId,
                                @Param("startedAt") long startedAt);

    @Update("UPDATE ai_turns SET interrupt_requested = 1, "
            + "dispatch_status = CASE WHEN dispatch_status = 'running' "
            + "THEN 'cancelling' ELSE dispatch_status END "
            + "WHERE turn_id = #{turnId} AND thread_id = #{threadId} "
            + "AND protocol_status = 'inProgress'")
    int requestProtocolTurnInterrupt(@Param("threadId") String threadId,
                                     @Param("turnId") String turnId);

    @Select("SELECT COUNT(*) FROM ai_turns WHERE thread_id = #{threadId} "
            + "AND dispatch_status = 'cancelling' AND interrupt_requested = 1")
    int countInterruptRequestedTurns(@Param("threadId") String threadId);

    @Update("UPDATE ai_turns SET protocol_status = #{protocolStatus}, "
            + "dispatch_status = #{protocolStatus}, "
            + "completed_at = COALESCE(completed_at, #{completedAt}), "
            + "error_message = #{errorMessage}, "
            + "status = CASE WHEN NOT EXISTS "
            + "(SELECT 1 FROM ai_runs r WHERE r.turn_id = ai_turns.turn_id) "
            + "THEN 'discarded' ELSE status END "
            + "WHERE turn_id = #{turnId} AND protocol_status = 'inProgress'")
    int completeProtocolTurn(AiTurnRecord row);

    @Update("UPDATE ai_turns SET protocol_status = #{protocolStatus}, "
            + "dispatch_status = #{protocolStatus}, "
            + "completed_at = COALESCE(completed_at, #{completedAt}), "
            + "error_message = #{errorMessage}, "
            + "status = CASE WHEN NOT EXISTS "
            + "(SELECT 1 FROM ai_runs r WHERE r.turn_id = ai_turns.turn_id) "
            + "THEN 'discarded' ELSE status END "
            + "WHERE turn_id = #{turnId} AND protocol_status = 'inProgress' "
            + "AND EXISTS (SELECT 1 FROM ai_thread_leases l "
            + "WHERE l.thread_id = ai_turns.thread_id "
            + "AND l.lease_token = #{leaseToken} "
            + "AND l.expires_at > #{completedAt})")
    int completeProtocolTurnFenced(@Param("turnId") String turnId,
                                   @Param("protocolStatus") String protocolStatus,
                                   @Param("errorMessage") String errorMessage,
                                   @Param("completedAt") long completedAt,
                                   @Param("leaseToken") String leaseToken);

    @Update("UPDATE ai_turns SET dispatch_status = 'queued', started_at = NULL "
            + "WHERE turn_id = #{turnId} AND protocol_status = 'inProgress' "
            + "AND dispatch_status = 'running'")
    int requeueProtocolTurn(@Param("turnId") String turnId);

    // ── Agent 等待用户输入 ────────────────────────────────────────────────

    @Insert("INSERT INTO ai_user_input_requests (request_id, thread_id, turn_id, item_id, "
            + "request_type, prompt, options_json, allow_free_text, action_summary, "
            + "tool_name, arguments_hash, risk, status, answer, created_at, answered_at, "
            + "confirmation_consumed_at, expires_at) "
            + "VALUES (#{requestId}, #{threadId}, #{turnId}, #{itemId}, #{requestType}, "
            + "#{prompt}, #{optionsJson}, #{allowFreeText}, #{actionSummary}, #{toolName}, "
            + "#{argumentsHash}, #{risk}, #{status}, #{answer}, #{createdAt}, #{answeredAt}, "
            + "#{confirmationConsumedAt}, #{expiresAt})")
    int insertUserInputRequest(AiUserInputRequest row);

    @Select("SELECT * FROM ai_user_input_requests WHERE request_id = #{requestId}")
    AiUserInputRequest findUserInputRequest(@Param("requestId") String requestId);

    @Select("SELECT * FROM ai_user_input_requests WHERE thread_id = #{threadId} "
            + "AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1")
    AiUserInputRequest findPendingUserInputRequest(@Param("threadId") String threadId);

    @Update("UPDATE ai_user_input_requests SET status = 'EXPIRED' "
            + "WHERE thread_id = #{threadId} AND status = 'PENDING' "
            + "AND expires_at IS NOT NULL AND expires_at <= #{now}")
    int expireUserInputRequests(@Param("threadId") String threadId,
                                @Param("now") long now);

    @Update("UPDATE ai_user_input_requests SET status = 'EXPIRED' "
            + "WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= #{now}")
    int expireAllUserInputRequests(@Param("now") long now);

    @Update("UPDATE ai_user_input_requests SET status = 'ANSWERED', answer = #{answer}, "
            + "answered_at = #{answeredAt} WHERE request_id = #{requestId} "
            + "AND thread_id = #{threadId} AND status = 'PENDING' "
            + "AND (expires_at IS NULL OR expires_at > #{answeredAt})")
    int answerUserInputRequest(@Param("requestId") String requestId,
                               @Param("threadId") String threadId,
                               @Param("answer") String answer,
                               @Param("answeredAt") long answeredAt);

    @Update("UPDATE ai_user_input_requests SET confirmation_consumed_at = #{consumedAt} "
            + "WHERE request_id = #{requestId} AND thread_id = #{threadId} "
            + "AND request_type = 'CONFIRMATION' AND status = 'ANSWERED' "
            + "AND confirmation_consumed_at IS NULL AND tool_name = #{toolName} "
            + "AND arguments_hash = #{argumentsHash} "
            + "AND (expires_at IS NULL OR expires_at > #{consumedAt})")
    int consumeConfirmation(@Param("requestId") String requestId,
                            @Param("threadId") String threadId,
                            @Param("toolName") String toolName,
                            @Param("argumentsHash") String argumentsHash,
                            @Param("consumedAt") long consumedAt);

    @Update("UPDATE ai_turns SET status = #{status}, completed_at = #{completedAt} "
            + "WHERE turn_id = #{turnId} AND status = 'pending' "
            + "AND (#{leaseToken} IS NULL OR EXISTS "
            + "(SELECT 1 FROM ai_runs r JOIN ai_thread_leases l ON l.thread_id = r.thread_id "
            + "WHERE r.turn_id = ai_turns.turn_id AND r.lease_token = #{leaseToken} "
            + "AND l.lease_token = #{leaseToken} AND l.expires_at > #{completedAt}))")
    int finishTurn(@Param("turnId") String turnId,
                   @Param("status") String status,
                   @Param("completedAt") long completedAt,
                   @Param("leaseToken") String leaseToken);

    @Insert("INSERT INTO ai_messages (message_id, thread_id, turn_id, run_id, message_seq, status, "
            + "role, content, timestamp, attachments_json, nodes_json, review_json, plan_json) "
            + "VALUES (#{messageId}, #{threadId}, #{turnId}, #{runId}, "
            + "COALESCE(#{messageSeq}, (SELECT COALESCE(MAX(message_seq), 0) + 1 "
            + "FROM ai_messages WHERE thread_id = #{threadId})), "
            + "#{status}, #{role}, #{content}, #{timestamp}, #{attachmentsJson}, "
            + "#{nodesJson}, #{reviewJson}, #{planJson})")
    int insertMessage(AiMessageRecord row);

    @Update("UPDATE ai_messages SET run_id = #{runId} "
            + "WHERE thread_id = #{threadId} AND turn_id = #{turnId} "
            + "AND run_id IS NULL")
    int attachRunToTurnMessages(@Param("threadId") String threadId,
                                @Param("turnId") String turnId,
                                @Param("runId") String runId);

    @Select("SELECT * FROM ("
            + "SELECT m.*, r.status AS run_status, "
            + "t.protocol_status AS protocol_status, "
            + "t.dispatch_status AS dispatch_status, "
            + "t.error_message AS protocol_error_message, "
            + "t.answer_to_question_id AS answer_to_question_id "
            + "FROM ai_messages m "
            + "LEFT JOIN ai_runs r ON r.run_id = m.run_id "
            + "LEFT JOIN ai_turns t ON t.turn_id = m.turn_id "
            + "WHERE m.thread_id = #{threadId} "
            + "ORDER BY m.message_seq DESC, m.message_id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + ") recent ORDER BY message_seq ASC, message_id ASC")
    List<AiMessageRecord> listMessages(@Param("threadId") String threadId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_messages WHERE thread_id = #{threadId}")
    int countMessages(@Param("threadId") String threadId);

    /**
     * 模型上下文消息：成功 Turn，以及确实产生了可恢复 assistant 进度的中断 Turn。
     * 后者仍保持 discarded 展示状态，但 user/assistant 两条消息会一起进入后续记忆。
     */
    @Select("SELECT * FROM (SELECT m.* FROM ai_messages m WHERE m.thread_id = #{threadId} "
            + "AND (m.status = 'committed' OR (m.status = 'discarded' AND EXISTS ("
            + "SELECT 1 FROM ai_messages progress WHERE progress.turn_id = m.turn_id "
            + "AND progress.role = 'assistant' "
            + "AND progress.status = 'discarded' "
            + "AND TRIM(COALESCE(progress.content, '')) <> ''))) "
            + "ORDER BY m.message_seq DESC, m.message_id DESC LIMIT #{limit}) "
            + "ORDER BY message_seq ASC, message_id ASC")
    List<AiMessageRecord> recentContextMessages(@Param("threadId") String threadId,
                                                @Param("limit") int limit);

    @Select("SELECT m.* FROM ai_messages m WHERE m.thread_id = #{threadId} "
            + "AND m.message_seq = #{messageSeq} AND (m.status = 'committed' "
            + "OR (m.status = 'discarded' AND EXISTS (SELECT 1 FROM ai_messages progress "
            + "WHERE progress.turn_id = m.turn_id AND progress.role = 'assistant' "
            + "AND progress.status = 'discarded' "
            + "AND TRIM(COALESCE(progress.content, '')) <> ''))) LIMIT 1")
    AiMessageRecord findContextMessageBySequence(@Param("threadId") String threadId,
                                                 @Param("messageSeq") long messageSeq);

    @Update("UPDATE ai_messages SET status = #{status} "
            + "WHERE thread_id = #{threadId} AND turn_id = #{turnId}")
    int updateTurnMessageStatus(@Param("threadId") String threadId,
                                @Param("turnId") String turnId,
                                @Param("status") String status);

    @Update("UPDATE ai_messages SET status = #{status} "
            + "WHERE thread_id = #{threadId} AND turn_id = #{turnId} "
            + "AND EXISTS (SELECT 1 FROM ai_runs r JOIN ai_thread_leases l "
            + "ON l.thread_id = r.thread_id WHERE r.turn_id = ai_messages.turn_id "
            + "AND r.lease_token = #{leaseToken} AND l.lease_token = #{leaseToken} "
            + "AND l.expires_at > #{writeAt})")
    int updateTurnMessageStatusFenced(@Param("threadId") String threadId,
                                      @Param("turnId") String turnId,
                                      @Param("status") String status,
                                      @Param("leaseToken") String leaseToken,
                                      @Param("writeAt") long writeAt);

    @Update("UPDATE ai_messages SET content = #{content}, nodes_json = #{nodesJson}, "
            + "review_json = #{reviewJson}, plan_json = #{planJson}, status = #{status} "
            + "WHERE message_id = #{messageId}")
    int updateMessage(AiMessageRecord row);

    @Update("UPDATE ai_messages SET content = #{content}, nodes_json = #{nodesJson}, "
            + "review_json = #{reviewJson}, plan_json = #{planJson}, status = #{status} "
            + "WHERE message_id = #{messageId} AND EXISTS "
            + "(SELECT 1 FROM ai_runs r JOIN ai_thread_leases l ON l.thread_id = r.thread_id "
            + "WHERE r.run_id = ai_messages.run_id AND r.lease_token = #{leaseToken} "
            + "AND l.lease_token = #{leaseToken} AND l.expires_at > #{writeAt})")
    int updateMessageFenced(@Param("messageId") String messageId,
                            @Param("status") String status,
                            @Param("content") String content,
                            @Param("nodesJson") String nodesJson,
                            @Param("reviewJson") String reviewJson,
                            @Param("planJson") String planJson,
                            @Param("leaseToken") String leaseToken,
                            @Param("writeAt") long writeAt);

    @Insert("INSERT INTO ai_runs (run_id, thread_id, turn_id, status, started_at, finished_at, duration_ms, "
            + "config_id, input, output, error_message, error_category, raw_error_message, "
            + "tool_call_count, runtime_json, trace_id, trace_json, lease_token) "
            + "VALUES (#{runId}, #{threadId}, #{turnId}, #{status}, #{startedAt}, #{finishedAt}, #{durationMs}, "
            + "#{configId}, #{input}, #{output}, #{errorMessage}, #{errorCategory}, #{rawErrorMessage}, "
            + "#{toolCallCount}, #{runtimeJson}, #{traceId}, #{traceJson}, #{leaseToken})")
    int insertRun(AiRunRecord row);

    @Insert("INSERT INTO ai_runs (run_id, thread_id, turn_id, status, started_at, finished_at, duration_ms, "
            + "config_id, input, output, error_message, error_category, raw_error_message, "
            + "tool_call_count, runtime_json, trace_id, trace_json, lease_token) "
            + "SELECT #{row.runId}, #{row.threadId}, #{row.turnId}, #{row.status}, #{row.startedAt}, "
            + "#{row.finishedAt}, #{row.durationMs}, #{row.configId}, #{row.input}, #{row.output}, "
            + "#{row.errorMessage}, #{row.errorCategory}, #{row.rawErrorMessage}, #{row.toolCallCount}, "
            + "#{row.runtimeJson}, #{row.traceId}, #{row.traceJson}, #{row.leaseToken} "
            + "WHERE EXISTS (SELECT 1 FROM ai_thread_leases l WHERE l.thread_id = #{row.threadId} "
            + "AND l.lease_token = #{row.leaseToken} AND l.expires_at > #{writeAt})")
    int insertRunFenced(@Param("row") AiRunRecord row,
                        @Param("writeAt") long writeAt);

    @Update("UPDATE ai_runs SET status = #{status}, finished_at = #{finishedAt}, duration_ms = #{durationMs}, "
            + "output = #{output}, error_message = #{errorMessage}, error_category = #{errorCategory}, "
            + "raw_error_message = #{rawErrorMessage}, tool_call_count = #{toolCallCount} "
            + "WHERE run_id = #{runId} AND status = 'running' "
            + "AND (#{leaseToken} IS NULL OR (lease_token = #{leaseToken} AND EXISTS "
            + "(SELECT 1 FROM ai_thread_leases l WHERE l.thread_id = ai_runs.thread_id "
            + "AND l.lease_token = #{leaseToken} AND l.expires_at > #{finishedAt})))")
    int finishRun(AiRunRecord row);

    @Update("UPDATE ai_runs SET trace_json = #{traceJson} WHERE run_id = #{runId}")
    int updateRunTrace(AiRunRecord row);

    @Update("UPDATE ai_runs SET trace_json = #{traceJson} WHERE run_id = #{runId} "
            + "AND lease_token = #{leaseToken} AND EXISTS "
            + "(SELECT 1 FROM ai_thread_leases l WHERE l.thread_id = ai_runs.thread_id "
            + "AND l.lease_token = #{leaseToken} AND l.expires_at > #{writeAt})")
    int updateRunTraceFenced(@Param("runId") String runId,
                             @Param("traceJson") String traceJson,
                             @Param("leaseToken") String leaseToken,
                             @Param("writeAt") long writeAt);

    // ── 可重放事件日志 ───────────────────────────────────────────────────────
    @Insert("INSERT INTO ai_events (event_id, run_id, thread_id, turn_id, item_id, "
            + "subagent_invocation_id, event_seq, timestamp, name, data_json) "
            + "SELECT #{eventId}, #{runId}, #{threadId}, #{turnId}, #{itemId}, "
            + "#{subagentInvocationId}, #{eventSeq}, #{timestamp}, #{name}, #{dataJson} "
            + "WHERE #{leaseToken} IS NULL OR EXISTS "
            + "(SELECT 1 FROM ai_thread_leases l WHERE l.thread_id = #{threadId} "
            + "AND l.lease_token = #{leaseToken} AND l.expires_at > #{writeAt})")
    int insertEvent(@Param("eventId") String eventId,
                    @Param("runId") String runId,
                    @Param("threadId") String threadId,
                    @Param("turnId") String turnId,
                    @Param("itemId") String itemId,
                    @Param("subagentInvocationId") String subagentInvocationId,
                    @Param("eventSeq") long eventSeq,
                    @Param("timestamp") long timestamp,
                    @Param("name") String name,
                    @Param("dataJson") String dataJson,
                    @Param("leaseToken") String leaseToken,
                    @Param("writeAt") long writeAt);

    @Select("SELECT * FROM ai_events WHERE thread_id = #{threadId} AND event_seq > #{afterSeq} "
            + "ORDER BY event_seq ASC LIMIT #{limit}")
    List<AiEventRecord> listEventsAfter(@Param("threadId") String threadId,
                                        @Param("afterSeq") long afterSeq,
                                        @Param("limit") int limit);

    @Select("SELECT * FROM ai_events WHERE run_id = #{runId} ORDER BY event_seq ASC")
    List<AiEventRecord> listEventsByRun(@Param("runId") String runId);

    @Select("SELECT COALESCE(MAX(event_seq), 0) FROM ai_events WHERE thread_id = #{threadId}")
    long findLastEventSeq(@Param("threadId") String threadId);

    @Select("SELECT COUNT(*) FROM ai_events WHERE thread_id = #{threadId} "
            + "AND turn_id = #{turnId} AND name = 'turn/completed'")
    int countTurnCompletedEvents(@Param("threadId") String threadId,
                                 @Param("turnId") String turnId);

    @Select("SELECT COALESCE(MAX(event_seq) - 1, 0) FROM ai_events "
            + "WHERE thread_id = #{threadId} AND name = 'turn/started'")
    long findLatestTurnStartSeq(@Param("threadId") String threadId);

    @Select("SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM ai_events "
            + "WHERE thread_id = #{threadId} AND name = 'turn/started') THEN 1 "
            + "WHEN EXISTS (SELECT 1 FROM ai_events WHERE thread_id = #{threadId} "
            + "AND name = 'turn/completed' AND turn_id = "
            + "(SELECT turn_id FROM ai_events WHERE thread_id = #{threadId} "
            + "AND name = 'turn/started' ORDER BY event_seq DESC LIMIT 1)) "
            + "THEN 1 ELSE 0 END")
    int hasLatestTurnCompletedEvent(@Param("threadId") String threadId);

    // ── 跨实例线程执行租约 ───────────────────────────────────────────────────
    @Insert("INSERT INTO ai_thread_leases "
            + "(thread_id, owner_id, lease_token, acquired_at, heartbeat_at, expires_at) "
            + "VALUES (#{threadId}, #{ownerId}, #{leaseToken}, #{acquiredAt}, "
            + "#{heartbeatAt}, #{expiresAt}) "
            + "ON CONFLICT(thread_id) DO UPDATE SET owner_id = excluded.owner_id, "
            + "lease_token = excluded.lease_token, acquired_at = excluded.acquired_at, "
            + "heartbeat_at = excluded.heartbeat_at, expires_at = excluded.expires_at "
            + "WHERE ai_thread_leases.expires_at <= #{acquiredAt}")
    int acquireThreadLease(AiThreadLeaseRecord row);

    @Update("UPDATE ai_thread_leases SET heartbeat_at = #{heartbeatAt}, expires_at = #{expiresAt} "
            + "WHERE thread_id = #{threadId} AND owner_id = #{ownerId} "
            + "AND lease_token = #{leaseToken} AND expires_at > #{heartbeatAt}")
    int renewThreadLease(AiThreadLeaseRecord row);

    @Delete("DELETE FROM ai_thread_leases WHERE thread_id = #{threadId} "
            + "AND owner_id = #{ownerId} AND lease_token = #{leaseToken}")
    int releaseThreadLease(AiThreadLeaseRecord row);

    @Select("SELECT * FROM ai_thread_leases WHERE expires_at <= #{now} ORDER BY expires_at ASC")
    List<AiThreadLeaseRecord> listExpiredThreadLeases(@Param("now") long now);

    @Update("UPDATE ai_thread_leases SET owner_id = #{ownerId}, lease_token = #{leaseToken}, "
            + "acquired_at = #{acquiredAt}, heartbeat_at = #{heartbeatAt}, expires_at = #{expiresAt} "
            + "WHERE thread_id = #{threadId} AND lease_token = #{previousToken} "
            + "AND expires_at <= #{acquiredAt}")
    int claimExpiredThreadLease(@Param("threadId") String threadId,
                                @Param("previousToken") String previousToken,
                                @Param("ownerId") String ownerId,
                                @Param("leaseToken") String leaseToken,
                                @Param("acquiredAt") long acquiredAt,
                                @Param("heartbeatAt") long heartbeatAt,
                                @Param("expiresAt") long expiresAt);

    @Select("SELECT r.thread_id, r.turn_id, r.run_id, r.started_at, "
            + "(SELECT message_id FROM ai_messages m WHERE m.run_id = r.run_id "
            + "AND m.role = 'assistant' LIMIT 1) AS assistant_message_id "
            + "FROM ai_runs r WHERE r.thread_id = #{threadId} AND r.status = 'running'")
    List<AiOrphanedRunRecord> listRunningRuns(@Param("threadId") String threadId);

    @Select("SELECT DISTINCT t.thread_id FROM ai_turns t "
            + "WHERE t.dispatch_status = 'running' "
            + "AND NOT EXISTS (SELECT 1 FROM ai_thread_leases l "
            + "WHERE l.thread_id = t.thread_id AND l.expires_at > #{now}) "
            + "AND EXISTS (SELECT 1 FROM ai_runs r "
            + "WHERE r.turn_id = t.turn_id AND r.status = 'running')")
    List<String> listThreadsWithStuckRunningTurns(@Param("now") long now);

    @Update("UPDATE ai_messages SET status = 'discarded' WHERE run_id = #{runId}")
    int discardRunMessages(@Param("runId") String runId);

    @Update("UPDATE ai_turns SET status = 'discarded', completed_at = #{finishedAt}, "
            + "protocol_status = 'failed', dispatch_status = 'failed', "
            + "error_message = '执行实例心跳超时，任务已自动收口' "
            + "WHERE turn_id = #{turnId} AND status = 'pending'")
    int discardOrphanedTurn(@Param("turnId") String turnId,
                            @Param("finishedAt") long finishedAt);

    @Update("UPDATE ai_runs SET status = 'failed', finished_at = #{finishedAt}, "
            + "duration_ms = MAX(0, #{finishedAt} - started_at), "
            + "error_category = 'orphaned', error_message = #{message}, "
            + "raw_error_message = #{message} "
            + "WHERE run_id = #{runId} AND status = 'running'")
    int failOrphanedRun(@Param("runId") String runId,
                        @Param("finishedAt") long finishedAt,
                        @Param("message") String message);

    @Update("UPDATE ai_threads SET run_status = 'failed', last_active_at = #{finishedAt} "
            + "WHERE thread_id = #{threadId}")
    int failOrphanedThread(@Param("threadId") String threadId,
                           @Param("finishedAt") long finishedAt);

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
