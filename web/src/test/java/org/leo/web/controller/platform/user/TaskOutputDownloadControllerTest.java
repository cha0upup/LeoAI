package org.leo.web.controller.platform.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskOutputDownloadControllerTest {
    private final String userId = "task-download-fixture-" + UUID.randomUUID();
    private final Path workspace = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath();
    private final TaskOutputDownloadController controller = new TaskOutputDownloadController();

    @AfterEach
    void deleteFixture() throws IOException {
        FileSystemUtils.deleteRecursively(workspace.getParent());
    }

    @Test
    void authenticatedUserCanDownloadNestedTaskOutputWithTheExistingFilenameContract() throws Exception {
        String path = "ai-tasks/job/files/reports/note.txt";
        writeFixture(path);
        var response = new MockHttpServletResponse();

        controller.download(authenticatedRequest(), path, "中文报告.txt", response);

        assertEquals(200, response.getStatus());
        assertEquals("fixture", response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("中文报告.txt", ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
        assertRejected(authenticatedRequest(), "ai-tasks/job/files/missing.txt", 404);
    }

    @Test
    void downloadRequiresLoginAndExcludesNonOutputFiles() throws Exception {
        writeFixture("imports/old.txt");
        writeFixture("ai-tasks/job/.internal/internal.txt");

        assertRejected(new MockHttpServletRequest(), "ai-tasks/job/files/note.txt", 401);
        assertRejected(authenticatedRequest(), "imports/old.txt", 404);
        assertRejected(authenticatedRequest(), "ai-tasks/job/.internal/internal.txt", 400);
    }

    private void assertRejected(MockHttpServletRequest request, String path, int status) {
        var response = new MockHttpServletResponse();
        controller.download(request, path, null, response);
        assertEquals(status, response.getStatus(), path);
        assertNull(response.getHeader("Content-Disposition"));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    private void writeFixture(String path) throws IOException {
        Path file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fixture");
    }

    private MockHttpServletRequest authenticatedRequest() {
        User user = new User();
        user.setUserId(userId);
        user.setUserName("task-download-fixture");
        var request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", user);
        return request;
    }
}
