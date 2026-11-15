package org.leo.ai.thread;

import org.leo.core.entity.AiUserInputRequest;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** Persistence boundary for agent user-input and confirmation requests. */
@Repository
public class AiUserInputRepository {

    private final AiConversationMapper mapper;

    public AiUserInputRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public AiUserInputRequest find(String requestId) {
        return blank(requestId) ? null : mapper.findUserInputRequest(requestId.trim());
    }

    public AiUserInputRequest findPending(String threadId) {
        if (blank(threadId)) return null;
        String normalized = threadId.trim();
        mapper.expireUserInputRequests(normalized, System.currentTimeMillis());
        return mapper.findPendingUserInputRequest(normalized);
    }

    public AiUserInputRequest create(AiUserInputRequest request) {
        if (request == null || blank(request.getThreadId())) {
            throw new IllegalArgumentException("用户输入请求缺少 threadId");
        }
        AiUserInputRequest pending = findPending(request.getThreadId());
        if (pending != null) return pending;
        try {
            if (mapper.insertUserInputRequest(request) != 1) {
                throw new IllegalStateException("创建用户输入请求失败");
            }
        } catch (DataIntegrityViolationException conflict) {
            AiUserInputRequest winner = findPending(request.getThreadId());
            if (winner != null) return winner;
            throw conflict;
        }
        return request;
    }

    public boolean answer(String requestId, String threadId, String answer) {
        if (blank(requestId) || blank(threadId) || answer == null || answer.isBlank()) {
            return false;
        }
        return mapper.answerUserInputRequest(
                requestId.trim(), threadId.trim(), answer.trim(),
                System.currentTimeMillis()) == 1;
    }

    /** A single conditional update prevents concurrent consumers from reusing a confirmation. */
    public boolean consumeConfirmation(String requestId, String threadId,
                                       String toolName, String argumentsHash,
                                       long consumedAt) {
        if (blank(requestId) || blank(threadId)
                || blank(toolName) || blank(argumentsHash)) return false;
        return mapper.consumeConfirmation(requestId.trim(), threadId.trim(),
                toolName.trim(), argumentsHash.trim(), consumedAt) == 1;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
