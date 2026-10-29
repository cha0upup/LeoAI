package org.leo.web.controller.agentnode.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.User;
import org.leo.core.repository.session.AgentSessionFileRepository;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.web.util.AuditLogUtil;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentNodeFileDownloadTest {
    @TempDir Path temp;
    private final AgentNodeFileController controller = new AgentNodeFileController(mock(AgentSessionFileRepository.class));

    @Test
    void streamsTaskStorageWithoutMovingItIntoTheWorkspace() throws Exception {
        Path file = temp.resolve("users/fixture-user/downloads/task.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fixture task data");
        try (var paths = mockStatic(AgentNodeSessionWorkDirUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            paths.when(AgentNodeSessionWorkDirUtil::getRootDir).thenReturn(temp.toFile());
            var response = new MockHttpServletResponse();
            controller.downloadLocalFile(request(), "downloads/task.txt", "任务 文件.txt", response);
            assertEquals(200, response.getStatus());
            assertEquals("fixture task data", response.getContentAsString());
            assertEquals("任务 文件.txt", ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"empty,400", "../other-user/file.txt,403", "downloads/missing.txt,404"})
    void preservesTaskDownloadPathChecks(String path, int status) {
        try (var paths = mockStatic(AgentNodeSessionWorkDirUtil.class)) {
            paths.when(AgentNodeSessionWorkDirUtil::getRootDir).thenReturn(temp.toFile());
            var response = new MockHttpServletResponse();
            controller.downloadLocalFile(request(), "empty".equals(path) ? "" : path, null, response);
            assertEquals(status, response.getStatus());
            assertEquals(0, response.getContentAsByteArray().length);
        }
    }

    @Test
    void requiresLoginForTaskDownloads() {
        var response = new MockHttpServletResponse();
        controller.downloadLocalFile(new MockHttpServletRequest(), "downloads/task.txt", null, response);
        assertEquals(401, response.getStatus());
    }

    private MockHttpServletRequest request() {
        User user = new User();
        user.setUserId("fixture-user");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", user);
        return request;
    }
}
