package org.leo.web.service;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.entity.AiUserInputRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 数据库权威的 Codex 式 Turn 命令状态机。 */
@Service
public class AiTurnProtocolService {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    private static final String STATUS_CANCELLING = "cancelling";
    private static final String STATUS_IN_PROGRESS = "inProgress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_INTERRUPTED = "interrupted";
    private static final String STATUS_FAILED = "failed";

    private final AiConversationStoreService store;

    public AiTurnProtocolService(AiConversationStoreService store) {
        this.store = store;
    }

    private TurnSnapshot findByClientId(
            String threadId, String clientUserMessageId) {
        return snapshot(store.findProtocolTurnByClientId(
                threadId, clientUserMessageId));
    }

    public Reservation begin(String threadId,
                             String clientUserMessageId,
                             String commandScope,
                             String commandJson,
                             String userContent,
                             Object attachments) {
        return begin(threadId, clientUserMessageId, commandScope, commandJson,
                userContent, attachments, null);
    }

    public Reservation begin(String threadId,
                             String clientUserMessageId,
                             String commandScope,
                             String commandJson,
                             String userContent,
                             Object attachments,
                             String answerToQuestionId) {
        String clientId = isBlank(clientUserMessageId)
                ? UUID.randomUUID().toString() : clientUserMessageId.trim();
        TurnSnapshot duplicate = findByClientId(threadId, clientId);
        if (duplicate != null) {
            requireSameCommand(duplicate, commandScope, commandJson);
            return new Reservation(duplicate, true);
        }

        long now = System.currentTimeMillis();
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId("turn-" + UUID.randomUUID());
        row.setThreadId(threadId);
        row.setProtocolStatus(STATUS_IN_PROGRESS);
        row.setDispatchStatus(STATUS_QUEUED);
        row.setCommandScope(commandScope);
        row.setCommandJson(commandJson);
        row.setClientUserMessageId(clientId);
        row.setUserItemId("item-" + UUID.randomUUID());
        row.setAssistantItemId("item-" + UUID.randomUUID());
        row.setCreatedAt(now);
        row.setInterruptRequested(false);
        row.setAnswerToQuestionId(emptyToNull(answerToQuestionId));
        if (store.reserveProtocolTurn(
                row, userContent, attachments, answerToQuestionId)) {
            return new Reservation(snapshot(row), false);
        }

        duplicate = findByClientId(threadId, clientId);
        if (duplicate != null) {
            requireSameCommand(duplicate, commandScope, commandJson);
            return new Reservation(duplicate, true);
        }
        throw new IllegalStateException("Turn 创建失败");
    }

    public TurnSnapshot findNextQueued(String threadId) {
        return snapshot(store.findNextQueuedProtocolTurn(threadId));
    }

    public List<String> listDispatchableThreadIds() {
        return store.listDispatchableProtocolThreadIds();
    }

    public ThreadSnapshot snapshotThread(String threadId, String fallbackStatus) {
        List<TurnSnapshot> inProgress = store
                .listInProgressProtocolTurns(threadId)
                .stream()
                .map(this::snapshot)
                .toList();
        TurnSnapshot active = inProgress.stream()
                .filter(turn -> STATUS_RUNNING.equals(turn.status())
                        || STATUS_CANCELLING.equals(turn.status()))
                .findFirst()
                .orElse(null);
        List<TurnSnapshot> queued = inProgress.stream()
                .filter(turn -> STATUS_QUEUED.equals(turn.status()))
                .toList();
        AiUserInputRequest pendingUserInput =
                store.findPendingUserInputRequest(threadId);
        String normalizedFallback = pendingUserInput == null
                && STATUS_WAITING_FOR_USER.equals(fallbackStatus)
                ? STATUS_COMPLETED : fallbackStatus;
        String status = active != null
                ? active.status()
                : !queued.isEmpty()
                        ? STATUS_QUEUED
                        : pendingUserInput != null
                                ? STATUS_WAITING_FOR_USER
                                : normalizedFallback;
        return new ThreadSnapshot(
                status, !inProgress.isEmpty(), active, queued,
                pendingUserInput);
    }

    public StartClaim tryStart(String turnId) {
        long startedAt = System.currentTimeMillis();
        boolean claimed = store.claimProtocolTurnStart(turnId, startedAt);
        return new StartClaim(snapshot(store.findProtocolTurn(turnId)), claimed);
    }

    public TurnSnapshot requestInterrupt(String threadId, String turnId) {
        String targetTurnId = turnId;
        if (isBlank(targetTurnId)) {
            ThreadSnapshot thread = snapshotThread(threadId, null);
            TurnSnapshot target = thread.activeTurn() != null
                    ? thread.activeTurn()
                    : thread.queuedTurns().stream().findFirst().orElse(null);
            if (target == null) {
                throw new IllegalStateException("当前线程没有可停止的 Turn");
            }
            targetTurnId = target.id();
        }
        AiTurnRecord current = require(targetTurnId);
        if (!threadId.equals(current.getThreadId())) {
            throw new IllegalStateException("Turn 不存在或已被后续 Turn 替代");
        }
        if (!STATUS_IN_PROGRESS.equals(current.getProtocolStatus())) {
            return snapshot(current);
        }
        return snapshot(store.requestProtocolTurnInterrupt(
                threadId, targetTurnId));
    }

    public TurnSnapshot completeFromRuntime(
            String turnId, String runtimeStatus, String errorMessage,
            String leaseToken) {
        String status = switch (runtimeStatus != null ? runtimeStatus : "") {
            case "completed", STATUS_WAITING_FOR_USER -> STATUS_COMPLETED;
            case "cancelled", "interrupted" -> STATUS_INTERRUPTED;
            default -> STATUS_FAILED;
        };
        AiTurnRecord current = require(turnId);
        if (!STATUS_IN_PROGRESS.equals(current.getProtocolStatus())) {
            return snapshot(current);
        }
        return snapshot(store.completeProtocolTurn(
                turnId, status, errorMessage, System.currentTimeMillis(),
                leaseToken));
    }

    public TurnSnapshot failStart(String turnId, String message) {
        return failStart(turnId, message, null);
    }

    public TurnSnapshot failStart(String turnId, String message, String leaseToken) {
        return snapshot(store.completeProtocolTurn(
                turnId, STATUS_FAILED, message, System.currentTimeMillis(),
                leaseToken));
    }

    public TurnSnapshot requeue(String turnId) {
        return snapshot(store.requeueProtocolTurn(turnId));
    }

    private AiTurnRecord require(String turnId) {
        AiTurnRecord turn = store.findProtocolTurn(turnId);
        if (turn == null) throw new IllegalStateException("Turn 不存在");
        return turn;
    }

    private void requireSameCommand(TurnSnapshot existing,
                                    String commandScope,
                                    String commandJson) {
        if (!java.util.Objects.equals(existing.commandScope(), commandScope)
                || !java.util.Objects.equals(existing.commandJson(), commandJson)) {
            throw new IllegalStateException(
                    "clientUserMessageId 已被不同 Turn 命令使用");
        }
    }

    private TurnSnapshot snapshot(AiTurnRecord row) {
        if (row == null) return null;
        String status = STATUS_IN_PROGRESS.equals(row.getProtocolStatus())
                ? (isBlank(row.getDispatchStatus())
                    ? STATUS_RUNNING : row.getDispatchStatus())
                : row.getProtocolStatus();
        return new TurnSnapshot(
                row.getTurnId(), row.getThreadId(), status,
                row.getClientUserMessageId(), row.getUserItemId(),
                row.getAssistantItemId(),
                row.getCreatedAt() != null ? row.getCreatedAt() : 0L,
                row.getStartedAt(), row.getCompletedAt(),
                Boolean.TRUE.equals(row.getInterruptRequested()),
                row.getErrorMessage(), row.getCommandScope(),
                row.getCommandJson(), row.getAnswerToQuestionId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    public record Reservation(TurnSnapshot turn, boolean reused) {}

    public record StartClaim(TurnSnapshot turn, boolean claimed) {}

    public record ThreadSnapshot(String status,
                                 boolean executing,
                                 TurnSnapshot activeTurn,
                                 List<TurnSnapshot> queuedTurns,
                                 AiUserInputRequest pendingUserInput) {

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("runStatus", status);
            value.put("executing", executing);
            value.put("activeTurn",
                    activeTurn != null ? activeTurn.toMap() : null);
            value.put("queuedTurns", queuedTurns.stream()
                    .map(TurnSnapshot::toMap)
                    .toList());
            value.put("pendingTurnCount",
                    queuedTurns.size() + (activeTurn != null ? 1 : 0));
            value.put("pendingUserInput",
                    pendingUserInput != null ? pendingUserInput.toMap() : null);
            return value;
        }
    }

    public record TurnSnapshot(String id,
                               String threadId,
                               String status,
                               String clientUserMessageId,
                               String userItemId,
                               String assistantItemId,
                               long createdAt,
                               Long startedAt,
                               Long completedAt,
                               boolean interruptRequested,
                               String errorMessage,
                               String commandScope,
                               String commandJson,
                               String answerToQuestionId) {

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("threadId", threadId);
            value.put("status", status);
            value.put("clientUserMessageId", clientUserMessageId);
            value.put("createdAt", createdAt);
            value.put("startedAt", startedAt);
            value.put("completedAt", completedAt);
            value.put("interruptRequested", interruptRequested);
            value.put("answerToQuestionId", answerToQuestionId);
            value.put("items", List.of(
                    messageItem(userItemId, "user", "completed"),
                    messageItem(assistantItemId, "assistant", itemStatus())));
            value.put("error", errorMessage != null
                    ? Map.of("message", errorMessage) : null);
            return value;
        }

        private String itemStatus() {
            if (STATUS_COMPLETED.equals(status)) return "completed";
            if (STATUS_INTERRUPTED.equals(status)) return "interrupted";
            if (STATUS_FAILED.equals(status)) return "failed";
            if (STATUS_QUEUED.equals(status)) return "queued";
            if (STATUS_CANCELLING.equals(status)) return "cancelling";
            return "inProgress";
        }

        private Map<String, Object> messageItem(
                String itemId, String role, String itemStatus) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", itemId);
            item.put("type", "message");
            item.put("role", role);
            item.put("status", itemStatus);
            return item;
        }
    }
}
