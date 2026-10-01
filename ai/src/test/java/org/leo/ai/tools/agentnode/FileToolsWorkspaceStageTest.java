package org.leo.ai.tools.agentnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.config.AiWorkspaceProperties;
import org.leo.ai.service.workspace.AiWorkspaceService;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.leo.service.audit.AgentAuditService;
import org.leo.service.user.UserService;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileToolsWorkspaceStageTest {

    private static final String SESSION_ID = "stage-session";
    private static final String USER_ID = "user1";

    @TempDir
    Path tempDir;

    private String previousVfsPath;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        AiToolContext.clear();
        AgentNodeSessionContainer.removeSession(SESSION_ID);
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void completedDownloadIsImportedIntoCurrentThreadWorkspace() throws Exception {
        byte[] artifact = new byte[]{0, 1, 2, 3, 4};
        Path source = tempDir.resolve("downloaded-history.sqlite");
        Files.write(source, artifact);

        DownloadEngineService downloads = mock(DownloadEngineService.class);
        UploadEngineService uploads = mock(UploadEngineService.class);
        UserService users = mock(UserService.class);
        AgentAuditService audit = mock(AgentAuditService.class);
        AiWorkspaceService workspaces = new AiWorkspaceService(
                new AiWorkspaceProperties());
        FileTools tools = new FileTools(
                downloads, uploads, users, audit, workspaces);

        registerSession();
        User user = new User();
        user.setUserId(USER_ID);
        user.setPrivilege("user");
        AiToolContext.setFromMemoryId(SESSION_ID + ":thread-1");
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(user));

        when(downloads.progress(USER_ID, "task-1")).thenReturn(Map.of(
                "taskId", "task-1",
                "sessionId", SESSION_ID,
                "filePath", "/remote/History",
                "state", "COMPLETED",
                "downloadPath", "downloads/History"));
        when(uploads.resolveVfsFilePath("users/" + USER_ID + "/downloads/History"))
                .thenReturn(source);
        doNothing().when(uploads).validateReadPermission(eq(USER_ID), eq("user"), eq(source));
        when(users.getUserById(USER_ID)).thenReturn(user);

        Map<String, Object> result = tools.queryRemoteFileStage(
                "task-1", "input/browser/history.sqlite");

        assertTrue((Boolean) result.get("staged"));
        @SuppressWarnings("unchecked")
        Map<String, Object> workspaceFile = (Map<String, Object>) result.get("workspaceFile");
        assertEquals("input/browser/history.sqlite", workspaceFile.get("path"));
        Path imported = workspaces.workspaceFromContext().filesRoot()
                .resolve("input/browser/history.sqlite");
        assertArrayEquals(artifact, Files.readAllBytes(imported));
        verify(audit).logSuccess(eq(SESSION_ID), any(), eq("FILE_DOWNLOAD"),
                eq("AI完成文件采集"), eq("/remote/History"), any(),
                eq("文件已导入任务工作空间"));
    }

    private void registerSession() {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(SESSION_ID);
        session.setCreateByUser(USER_ID);
        session.setAgentNode(mock(AbstractAgentNode.class));
        AgentNodeSessionContainer.addSession(SESSION_ID, session);
    }
}
