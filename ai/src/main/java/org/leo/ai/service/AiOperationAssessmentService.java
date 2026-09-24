package org.leo.ai.service;

import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolDescriptor;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.thread.AiOperationAssessmentRepository;
import org.leo.core.entity.AiOperationAssessment;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Stores the Agent's semantic risk assessment until the exact operation executes.
 * Commands, SQL and file contents are intentionally not interpreted here.
 */
@Service
public class AiOperationAssessmentService {
    private static final long TTL_MS = 30L * 60L * 1_000L;

    private final AiToolCatalog toolCatalog;
    private final AiOperationAssessmentRepository repository;

    public AiOperationAssessmentService(AiToolCatalog toolCatalog,
                                        AiOperationAssessmentRepository repository) {
        this.toolCatalog = toolCatalog;
        this.repository = repository;
    }

    public Map<String, Object> assess(Object memoryId, String toolName,
                                      String argumentsJson, String riskLevel,
                                      Boolean requiresConfirmation, String reason,
                                      String impact, String rollback) {
        String normalizedTool = requireText(toolName, "toolName 不能为空");
        String normalizedArguments = requireText(argumentsJson, "argumentsJson 不能为空");
        AiToolDescriptor descriptor = toolCatalog.get(normalizedTool);
        if (descriptor.operation() == AiToolOperation.READ_ONLY || !descriptor.business()) {
            throw AiToolException.modelCorrectable(
                    "OPERATION_ASSESSMENT_NOT_REQUIRED",
                    "该工具不是 Puppet 或平台业务变更工具，不需要操作评估。",
                    "直接调用只读工具或内部控制工具，不要调用 assess_operation。");
        }
        String threadKey = effectiveThreadKey(memoryId);
        String userId = AiToolContext.getExecutionPolicy().getUserId();
        String canonicalArguments = AiUserInputService.canonicalArgumentsJson(normalizedArguments);
        String hash = AiUserInputService.confirmationArgumentsHash(canonicalArguments);
        boolean confirm = Boolean.TRUE.equals(requiresConfirmation);
        String normalizedRisk = normalizeRisk(riskLevel, confirm);
        if (("HIGH".equals(normalizedRisk) || "CRITICAL".equals(normalizedRisk)) && !confirm) {
            throw AiToolException.modelCorrectable(
                    "HIGH_RISK_CONFIRMATION_REQUIRED",
                    "HIGH 或 CRITICAL 风险操作必须请求用户确认。",
                    "将 requiresConfirmation 设为 true，并先调用 request_user_input(type=CONFIRMATION)。");
        }
        Assessment assessment = new Assessment(
                "assessment-" + java.util.UUID.randomUUID(),
                normalizedTool, canonicalArguments, hash, normalizedRisk, confirm,
                limit(reason, 2_000), limit(impact, 2_000), limit(rollback, 2_000),
                System.currentTimeMillis() + TTL_MS);
        AiOperationAssessment existing = repository.findPending(userId, threadKey, normalizedTool, hash);
        if (existing != null) return fromEntity(existing).toMap();
        repository.create(toEntity(assessment, userId, threadKey));
        return assessment.toMap();
    }

    public Assessment find(Object memoryId, String toolName, String argumentsJson) {
        String threadKey = effectiveThreadKey(memoryId);
        String userId = AiToolContext.getExecutionPolicy().getUserId();
        String hash = AiUserInputService.confirmationArgumentsHash(
                argumentsJson == null ? "{}" : argumentsJson);
        AiOperationAssessment row = repository.findPending(userId, threadKey, toolName, hash);
        return row == null ? null : fromEntity(row);
    }

    public boolean consume(Assessment assessment) {
        return assessment != null && repository.consume(assessment.key(), System.currentTimeMillis());
    }

    private String effectiveThreadKey(Object memoryId) {
        String threadId = AiToolContext.getThreadId();
        if (threadId != null && !threadId.isBlank()) return threadId.trim();
        return requireText(memoryId == null ? null : String.valueOf(memoryId), "AI 运行线程不存在");
    }

    private AiOperationAssessment toEntity(Assessment value, String userId, String threadId) {
        AiOperationAssessment row = new AiOperationAssessment();
        row.setAssessmentId(value.key());
        row.setUserId(userId);
        row.setThreadId(threadId);
        row.setToolName(value.toolName());
        row.setArgumentsJson(value.argumentsJson());
        row.setArgumentsHash(value.argumentsHash());
        row.setRiskLevel(value.riskLevel());
        row.setRequiresConfirmation(value.requiresConfirmation());
        row.setReason(value.reason());
        row.setImpact(value.impact());
        row.setRollback(value.rollback());
        row.setStatus("PENDING");
        row.setCreatedAt(System.currentTimeMillis());
        row.setExpiresAt(value.expiresAt());
        return row;
    }

    private Assessment fromEntity(AiOperationAssessment row) {
        return new Assessment(row.getAssessmentId(), row.getToolName(), row.getArgumentsJson(), row.getArgumentsHash(),
                row.getRiskLevel(), Boolean.TRUE.equals(row.getRequiresConfirmation()),
                row.getReason(), row.getImpact(), row.getRollback(), row.getExpiresAt());
    }

    private static String normalizeRisk(String value, boolean confirmation) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return confirmation ? "HIGH" : "LOW";
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> throw AiToolException.modelCorrectable(
                    "INVALID_OPERATION_RISK",
                    "riskLevel 只能是 LOW、MEDIUM、HIGH 或 CRITICAL。",
                    "重新评估操作并传入合法 riskLevel。");
        };
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw AiToolException.modelCorrectable(
                    "MISSING_OPERATION_ASSESSMENT_ARGUMENT", message,
                    "补充缺少的参数后重新调用。");
        }
        return value.trim();
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    public record Assessment(String key, String toolName, String argumentsJson, String argumentsHash,
                             String riskLevel, boolean requiresConfirmation,
                             String reason, String impact, String rollback,
                             long expiresAt) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "assessed", true,
                    "toolName", toolName,
                    "argumentsHash", argumentsHash,
                    "argumentsJson", argumentsJson,
                    "riskLevel", riskLevel,
                    "requiresConfirmation", requiresConfirmation,
                    "reason", reason == null ? "" : reason,
                    "impact", impact == null ? "" : impact,
                    "rollback", rollback == null ? "" : rollback,
                    "expiresAt", expiresAt);
        }
    }
}
