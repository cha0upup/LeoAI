package org.leo.web.dto.puppetnode.command;

import org.leo.web.exception.ApiException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A batch is confined to one authorized node session. */
public record TerminalBatchReadRequest(String sessionId, List<String> processIds) {
    public static TerminalBatchReadRequest normalize(TerminalBatchReadRequest request) {
        if (request == null) throw ApiException.badRequest("请求体不能为空");
        String sessionId = CommandExecRequest.requireText(request.sessionId, "sessionId");
        if (request.processIds == null || request.processIds.isEmpty() || request.processIds.size() > 16) {
            throw ApiException.badRequest("批量读取必须包含1至16个终端");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String value : request.processIds) {
            if (!ids.add(CommandExecRequest.normalizeProcessId(value))) {
                throw ApiException.badRequest("processIds不能重复");
            }
        }
        return new TerminalBatchReadRequest(sessionId, List.copyOf(ids));
    }
}
