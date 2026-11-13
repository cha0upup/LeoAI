package org.leo.web.controller.agentnode.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.agent.capability.FileCapable;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.core.infrastructure.persistence.session.AgentSessionFileRepository;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentNodeFileDownloadTest {
    @TempDir Path temp;
    private final AgentSessionFileRepository repository = mock(AgentSessionFileRepository.class);
    private final AgentNodeFileController controller = new AgentNodeFileController(repository);

    @ParameterizedTest
    @CsvSource({"100,false", "200,true"})
    void previewsNormalizeBothWireFormatsAndCacheTheInitialSlice(int code, boolean encoded) throws Exception {
        FileCapable node = mock(FileCapable.class);
        var params = new HashMap<String, Object>(Map.of("sessionId", "session", "path", "/preview.txt"));
        byte[] bytes = {1, 2};
        long totalSize = code == 100 ? 3L : 2L;
        when(node.fileDownloadChunk("/preview.txt", 1024 * 1024L, 0L)).thenReturn(Map.of(
                "code", code, "data", encoded ? "AQI=" : bytes, "length", totalSize, "nextOffset", 2L));
        try (var sessions = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            sessions.when(() -> ControllerUtil.requireCapability(params, FileCapable.class)).thenReturn(node);
            sessions.when(() -> ControllerUtil.getRequiredStringParam(params, "path")).thenReturn("/preview.txt");
            assertEquals(Map.of("data", "AQI=", "size", totalSize, "truncated", code == 100, "nextOffset", 2L),
                    controller.filePreview(params).get("data"));
            verify(repository).savePreview("session", "/preview.txt", bytes);
        }
    }

    @Test
    void failedPreviewsNeitherAuditSuccessNorWriteCache() throws Exception {
        FileCapable node = mock(FileCapable.class);
        var params = new HashMap<String, Object>(Map.of("sessionId", "session", "path", "/preview.txt"));
        when(node.fileDownloadChunk("/preview.txt", 1024 * 1024L, 0L))
                .thenReturn(null, Map.of("code", 500, "msg", "fixture failure"));
        try (var sessions = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            sessions.when(() -> ControllerUtil.requireCapability(params, FileCapable.class)).thenReturn(node);
            sessions.when(() -> ControllerUtil.getRequiredStringParam(params, "path")).thenReturn("/preview.txt");
            assertThrows(ApiException.class, () -> controller.filePreview(params));
            assertThrows(ApiException.class, () -> controller.filePreview(params));
            verifyNoInteractions(repository);
            audit.verifyNoInteractions();
        }
    }

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
