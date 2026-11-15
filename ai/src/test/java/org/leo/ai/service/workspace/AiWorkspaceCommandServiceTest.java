package org.leo.ai.service.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.config.AiWorkspaceProperties;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiExecutionPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkspaceCommandServiceTest {

    @TempDir
    Path tempDir;

    private AiWorkspaceService workspaceService;
    private AiWorkspaceCommandService commandService;
    private AiWorkspaceService.Workspace workspace;
    private ExecutorService worker;

    @BeforeEach
    void setUp() throws Exception {
        workspaceService = new AiWorkspaceService(new AiWorkspaceProperties());
        worker = Executors.newSingleThreadExecutor();
        commandService = new AiWorkspaceCommandService(workspaceService, worker);
        workspace = workspace("0123456789abcdef0123456789abcdef");
        AiToolContext.setExecutionPolicy(new AiExecutionPolicy("user1", "admin", "admin"));
    }

    @AfterEach
    void tearDown() throws Exception {
        commandService.shutdown();
        AiToolContext.clear();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void executesCommandInWorkspaceAndReportsFileChanges() throws Exception {
        String command = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "echo hello workspace>output.txt && echo WORKSPACE_HOME=%USERPROFILE%"
                : "printf 'hello workspace' > output.txt && printf 'WORKSPACE_HOME=%s\\n' \"$HOME\"";

        Map<String, Object> started = commandService.start(workspace, command, 10);
        String runId = String.valueOf(started.get("runId"));
        Map<String, Object> completed = awaitFinished(runId);

        assertEquals("COMPLETED", completed.get("status"));
        assertEquals(".", completed.get("workingDirectory"));
        assertTrue(Files.readString(workspace.filesRoot().resolve("output.txt"))
                .contains("hello workspace"));
        assertTrue(String.valueOf(completed.get("logTail"))
                .contains("WORKSPACE_HOME=" + workspace.filesRoot()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) completed.get("changedFiles");
        var output = changes.stream()
                .filter(change -> "output.txt".equals(change.get("path"))).findFirst().orElseThrow();
        assertEquals("CREATED", output.get("change"));
        assertEquals(workspaceService.readText(workspace, "output.txt", 1, 10).get("sha256"), output.get("sha256"));
        assertTrue(Files.isRegularFile(workspace.filesRoot()
                .resolve(String.valueOf(completed.get("logPath")))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingWhileWaitingForTheWorkspaceLockPreventsProcessStart(boolean shutdown) throws Exception {
        var lock = workspace.lock().writeLock();
        String runId;
        lock.lock();
        try {
            runId = String.valueOf(commandService.start(workspace, "echo unexpected > cancelled.txt", 10).get("runId"));
            Instant deadline = Instant.now().plusSeconds(5);
            while (!workspace.lock().hasQueuedThreads() && Instant.now().isBefore(deadline)) Thread.sleep(10L);
            assertTrue(workspace.lock().hasQueuedThreads(), "command must be waiting for the workspace lock");

            if (shutdown) commandService.shutdown();
            else commandService.cancel(workspace, runId);
            assertEquals("CANCELLED", commandService.status(workspace, runId).get("status"));
        } finally {
            lock.unlock();
        }
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

        assertFalse(Files.exists(workspace.filesRoot().resolve("cancelled.txt")));
        assertNull(commandService.status(workspace, runId).get("startedAt"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void cancellingAFinishedCommandPreservesItsResult(int exitCode) throws Exception {
        String runId = String.valueOf(commandService.start(workspace, "exit " + exitCode, 10).get("runId"));
        Map<String, Object> finished = awaitFinished(runId);

        Map<String, Object> cancelled = commandService.cancel(workspace, runId);

        assertEquals(finished.get("status"), cancelled.get("status"));
        assertEquals(exitCode, cancelled.get("exitCode"));
        assertEquals(finished.get("finishedAt"), cancelled.get("finishedAt"));
    }

    @Test
    void rejectsOrdinaryUsersBeforeStartingLocalProcesses() {
        AiToolContext.setExecutionPolicy(new AiExecutionPolicy("user1", "user1", "normal"));

        assertThrows(SecurityException.class, () -> commandService.start(workspace, "echo denied", 10));
        assertEquals(0, workspaceService.list(workspace, ".", 1, 10).get("returned"));
    }

    @Test
    void rejectsCommandsWithoutAnExecutionIdentity() {
        AiToolContext.clear();

        assertThrows(SecurityException.class, () -> commandService.start(workspace, "echo denied", 10));
    }

    @Test
    void rejectsAdministratorsUsingAnotherUsersWorkspace() {
        AiToolContext.setExecutionPolicy(new AiExecutionPolicy("other-admin", "admin", "admin"));

        assertThrows(SecurityException.class, () -> commandService.start(workspace, "echo denied", 10));
    }

    @Test
    void statusIsBoundToOwningWorkspace() throws Exception {
        Map<String, Object> started = commandService.start(workspace, "echo owned", 10);
        AiWorkspaceService.Workspace other = workspace("fedcba9876543210fedcba9876543210");

        assertThrows(IllegalArgumentException.class,
                () -> commandService.status(other, String.valueOf(started.get("runId"))));
    }

    private AiWorkspaceService.Workspace workspace(String id) throws Exception {
        Path base = tempDir.resolve(id);
        Path files = Files.createDirectories(base.resolve("files"));
        Path internal = Files.createDirectories(base.resolve(".internal"));
        for (String directory : List.of("versions", "trash", "tmp")) Files.createDirectories(internal.resolve(directory));
        return new AiWorkspaceService.Workspace("user1", id, tempDir, base, files, internal,
                new ReentrantReadWriteLock());
    }

    private Map<String, Object> awaitFinished(String runId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> status = commandService.status(workspace, runId);
            if (status.get("finishedAt") != null) return status;
            Thread.sleep(25L);
        }
        throw new AssertionError("workspace command did not finish");
    }
}
