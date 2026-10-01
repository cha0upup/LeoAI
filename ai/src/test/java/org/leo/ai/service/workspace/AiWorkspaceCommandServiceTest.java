package org.leo.ai.service.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.config.AiWorkspaceProperties;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkspaceCommandServiceTest {

    @TempDir
    Path tempDir;

    private String previousVfsPath;
    private AiWorkspaceService workspaceService;
    private AiWorkspaceCommandService commandService;
    private AiWorkspaceService.Workspace workspace;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        workspaceService = new AiWorkspaceService(new AiWorkspaceProperties());
        commandService = new AiWorkspaceCommandService(workspaceService);
        workspace = workspaceService.open("user1", "0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void tearDown() {
        commandService.shutdown();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void executesCommandInWorkspaceAndReportsFileChanges() throws Exception {
        String command = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "echo hello workspace>output.txt && cd"
                : "printf 'hello workspace' > output.txt && pwd";

        Map<String, Object> started = commandService.start(workspace, command, 10);
        String runId = String.valueOf(started.get("runId"));
        Map<String, Object> completed = awaitFinished(runId);

        assertEquals("COMPLETED", completed.get("status"));
        assertEquals(".", completed.get("workingDirectory"));
        assertTrue(Files.readString(workspace.filesRoot().resolve("output.txt"))
                .contains("hello workspace"));
        assertTrue(String.valueOf(completed.get("logTail"))
                .contains(workspace.filesRoot().toString()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) completed.get("changedFiles");
        assertTrue(changes.stream().anyMatch(change ->
                "output.txt".equals(change.get("path"))
                        && "CREATED".equals(change.get("change"))));
        assertTrue(Files.isRegularFile(workspace.filesRoot()
                .resolve(String.valueOf(completed.get("logPath")))));
    }

    @Test
    void statusIsBoundToOwningWorkspace() {
        Map<String, Object> started = commandService.start(workspace, "echo owned", 10);
        AiWorkspaceService.Workspace other = workspaceService.open(
                "user1", "fedcba9876543210fedcba9876543210");

        assertThrows(IllegalArgumentException.class,
                () -> commandService.status(other, String.valueOf(started.get("runId"))));
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
