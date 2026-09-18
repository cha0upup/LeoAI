package org.leo.ai.thread;

import org.leo.core.entity.AiSubagentInvocation;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/** Persistence boundary for parent-to-child Agent invocation records. */
@Repository
public class AiSubagentInvocationRepository {

    private final AiConversationMapper mapper;

    public AiSubagentInvocationRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AiSubagentInvocation row) {
        if (row.getStatus() == null) {
            row.setStatus(AiSubagentInvocation.STATUS_PENDING);
        }
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(System.currentTimeMillis());
        }
        mapper.insertSubagentInvocation(row);
    }

    public void update(AiSubagentInvocation row) {
        if (row == null || row.getInvocationId() == null) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (AiSubagentInvocation.isTerminal(row.getStatus()) && row.getCompletedAt() == null) {
            row.setCompletedAt(System.currentTimeMillis());
        } else if (!AiSubagentInvocation.isTerminal(row.getStatus())) {
            row.setCompletedAt(null);
        }
        mapper.updateSubagentInvocation(row);
    }

    public List<AiSubagentInvocation> listByParentThread(String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            return Collections.emptyList();
        }
        return mapper.listSubagentInvocations(parentThreadId);
    }
}
