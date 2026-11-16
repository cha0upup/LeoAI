package org.leo.ai.service.workspace;

import jakarta.annotation.PreDestroy;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.concurrent.TaskExecutors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 工作空间本地命令共用两个 worker、最多排队 32 项，并提供异步状态与文件变更摘要。 */
@Service
public class AiWorkspaceCommandService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static final int MAX_COMMAND_CHARS = 20_000;
    private static final Set<String> INHERITED_ENVIRONMENT = Set.of(
            "PATH", "Path", "PATHEXT", "SystemRoot", "COMSPEC", "LANG", "LC_ALL", "LC_CTYPE", "TERM");

    private final AiWorkspaceService workspaceService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    @Autowired
    public AiWorkspaceCommandService(AiWorkspaceService workspaceService) {
        this(workspaceService, TaskExecutors.bounded(2, 32, "ai-workspace-command-"));
    }

    AiWorkspaceCommandService(AiWorkspaceService workspaceService, ExecutorService executor) {
        this.workspaceService = workspaceService;
        this.executor = executor;
    }

    public Map<String, Object> start(AiWorkspaceService.Workspace workspace,
                                     String command, Integer timeoutSeconds) {
        var policy = AiToolContext.requireExecutionPolicy();
        if (!policy.isAdmin() || !Objects.equals(policy.getUserId(), workspace.userId())) {
            throw new SecurityException("本地命令仅限管理员在本人工作空间中执行");
        }
        String safeCommand = requireCommand(command);
        int safeTimeout = timeoutSeconds == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS));
        cleanupExpired();
        String runId = UUID.randomUUID().toString();
        RunState state = new RunState(runId, workspace, safeCommand, safeTimeout);
        runs.put(runId, state);
        try {
            executor.execute(() -> execute(state));
        } catch (RejectedExecutionException error) {
            runs.remove(runId, state);
            throw error;
        }
        Map<String, Object> result = state.toMap();
        result.put("hint", "调用 workspaceExecStatus 查询输出和文件变更；命令工作目录为当前任务 files 目录。");
        return result;
    }

    public Map<String, Object> status(AiWorkspaceService.Workspace workspace,
                                      String runId) {
        RunState state = requireOwned(workspace, runId);
        synchronized (state) {
            Map<String, Object> result = state.toMap();
            result.put("logTail", tail(state.log, 20_000));
            result.put("changedFiles", state.changedFiles);
            result.put("logPath", state.logPath);
            return result;
        }
    }

    public Map<String, Object> cancel(AiWorkspaceService.Workspace workspace,
                                      String runId) {
        RunState state = requireOwned(workspace, runId);
        destroyProcessTree(state.cancel());
        return state.toMap();
    }

    private void execute(RunState state) {
        var workspaceLock = state.workspace.lock().writeLock();
        workspaceLock.lock();
        try {
            if (!state.begin()) return;
            Map<String, FileFingerprint> before = snapshot(state.workspace.filesRoot());
            ProcessBuilder builder = new ProcessBuilder(shellCommand(state.command));
            builder.directory(state.workspace.filesRoot().toFile());
            builder.redirectErrorStream(true);
            configureEnvironment(builder, state.workspace);
            Process process = state.launch(builder);
            if (process == null) return;
            Thread reader = new Thread(() -> readLog(state, process.getInputStream()),
                    "ai-workspace-command-log");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(state.timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                state.complete("TIMED_OUT", null);
                destroyProcessTree(process);
            } else {
                int exitCode = process.exitValue();
                state.complete(exitCode == 0 ? "COMPLETED" : "FAILED", exitCode);
            }
            reader.join(2_000L);
            synchronized (state) {
                state.changedFiles = changes(before, snapshot(state.workspace.filesRoot()));
            }
        } catch (IOException e) {
            appendLog(state, "Command start failed: " + e.getMessage() + "\n");
            state.complete("FAILED", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.complete("CANCELLED", null);
            destroyProcessTree(state.process);
        } catch (RuntimeException e) {
            appendLog(state, "Command result error: " + e.getMessage() + "\n");
            state.complete("FAILED", null);
        } finally {
            try {
                destroyProcessTree(state.process);
                synchronized (state) {
                    if (state.startedAt != null) {
                        state.logPath = "logs/command-" + state.runId + ".log";
                        try {
                            workspaceService.writeGeneratedBytes(
                                    state.workspace, state.logPath, state.log.toByteArray());
                        } catch (RuntimeException error) {
                            state.logPath = null;
                        }
                    }
                    state.finish();
                }
            } finally {
                workspaceLock.unlock();
            }
        }
    }

    private List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("/bin/sh", "-lc", command);
    }

    private void configureEnvironment(ProcessBuilder builder,
                                      AiWorkspaceService.Workspace workspace) {
        Map<String, String> environment = builder.environment();
        environment.entrySet().removeIf(entry -> !INHERITED_ENVIRONMENT.contains(entry.getKey())
                || entry.getValue().isBlank());
        if (!environment.containsKey("PATH") && !environment.containsKey("Path")) {
            environment.put("PATH", "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        }
        String filesRoot = workspace.filesRoot().toAbsolutePath().toString();
        environment.put("HOME", filesRoot);
        environment.put("USERPROFILE", filesRoot);
        environment.put("TMPDIR", workspace.internalRoot().resolve("tmp").toAbsolutePath().toString());
        environment.put("LEO_AI_WORKSPACE", filesRoot);
    }

    private Map<String, FileFingerprint> snapshot(Path root) throws IOException {
        Map<String, FileFingerprint> result = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !Files.isSymbolicLink(p)).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                result.put(relative, new FileFingerprint(Files.size(path), AiWorkspaceService.sha256(path)));
            }
        }
        return result;
    }

    private List<Map<String, Object>> changes(Map<String, FileFingerprint> before,
                                              Map<String, FileFingerprint> after) {
        Set<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String path : paths) {
            FileFingerprint previous = before.get(path);
            FileFingerprint current = after.get(path);
            if (previous != null && previous.equals(current)) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("path", path);
            change.put("change", previous == null ? "CREATED" : current == null ? "DELETED" : "MODIFIED");
            if (current != null) {
                change.put("size", current.size());
                change.put("sha256", current.sha256());
            }
            result.add(change);
        }
        return List.copyOf(result);
    }

    private void readLog(RunState state, InputStream input) {
        byte[] buffer = new byte[8_192];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                synchronized (state) {
                    long remaining = MAX_LOG_BYTES - state.log.size();
                    if (remaining > 0) state.log.write(buffer, 0, (int) Math.min(count, remaining));
                }
            }
        } catch (IOException ignored) { }
    }

    private void appendLog(RunState state, String text) {
        synchronized (state) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int remaining = (int) Math.max(0, MAX_LOG_BYTES - state.log.size());
            state.log.write(bytes, 0, Math.min(bytes.length, remaining));
        }
    }

    private String requireCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        if (command.length() > MAX_COMMAND_CHARS || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("command 超过长度上限或包含非法字符");
        }
        return command;
    }

    private RunState requireOwned(AiWorkspaceService.Workspace workspace, String runId) {
        RunState state = runs.get(runId);
        if (state == null || !state.workspace.userId().equals(workspace.userId())
                || !state.workspace.workspaceId().equals(workspace.workspaceId())) {
            throw new IllegalArgumentException("运行不存在或不属于当前任务");
        }
        return state;
    }

    private void destroyProcessTree(Process process) {
        if (process == null) return;
        try (var descendants = process.descendants()) {
            descendants.forEach(handle -> {
                try { handle.destroyForcibly(); }
                catch (RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
        try {
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) { }
    }

    private void cleanupExpired() {
        Instant cutoff = Instant.now().minusSeconds(24 * 60 * 60L);
        runs.entrySet().removeIf(entry -> entry.getValue().finishedAt != null
                && entry.getValue().finishedAt.isBefore(cutoff));
    }

    private String tail(ByteArrayOutputStream output, int maxChars) {
        String text = output.toString(StandardCharsets.UTF_8);
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    @PreDestroy
    public void shutdown() {
        TaskExecutors.shutdownNow(executor);
        for (RunState state : runs.values()) {
            state.cancel();
            destroyProcessTree(state.process);
        }
    }

    private record FileFingerprint(long size, String sha256) { }

    private static final class RunState {
        private final String runId;
        private final AiWorkspaceService.Workspace workspace;
        private final String command;
        private final int timeoutSeconds;
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();
        private String status = "QUEUED";
        private Integer exitCode;
        private Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Process process;
        private List<Map<String, Object>> changedFiles = List.of();
        private String logPath;

        private RunState(String runId, AiWorkspaceService.Workspace workspace,
                         String command, int timeoutSeconds) {
            this.runId = runId;
            this.workspace = workspace;
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
        }

        private synchronized boolean begin() {
            if (!"QUEUED".equals(status)) return false;
            status = "RUNNING";
            startedAt = Instant.now();
            return true;
        }

        private synchronized Process launch(ProcessBuilder builder) throws IOException {
            if (!"RUNNING".equals(status)) return null;
            process = builder.start();
            return process;
        }

        private synchronized Process cancel() {
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) return null;
            status = "CANCELLED";
            if (startedAt == null) finishedAt = Instant.now();
            return process;
        }

        private synchronized void complete(String outcome, Integer code) {
            if ("CANCELLED".equals(status)) return;
            status = outcome;
            if (code != null) exitCode = code;
        }

        private synchronized void finish() {
            process = null;
            if (finishedAt == null) finishedAt = Instant.now();
        }

        private synchronized Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("runId", runId);
            result.put("workspaceId", workspace.workspaceId());
            result.put("command", command);
            result.put("workingDirectory", ".");
            result.put("timeoutSeconds", timeoutSeconds);
            result.put("status", status);
            result.put("exitCode", exitCode);
            result.put("startedAt", startedAt != null ? startedAt.toString() : null);
            result.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
            return result;
        }
    }
}
