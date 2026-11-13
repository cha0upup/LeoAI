package org.leo.web.controller.agentnode.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.FileCapable;
import org.leo.service.transfer.DownloadEngineService;
import org.leo.service.transfer.UploadEngineService;
import org.leo.service.vfs.VfsFileAccess;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferEngineControllerTest {
    private final DownloadEngineService service = mock(DownloadEngineService.class);
    private final DownloadEngineController controller = new DownloadEngineController(service);
    private final UploadEngineService uploadService = mock(UploadEngineService.class);
    private final VfsFileAccess files = mock(VfsFileAccess.class);
    private final UploadEngineController uploadController = new UploadEngineController(
            uploadService, files);

    @Test
    void uploadSourceAccessIsCheckedBeforeAnyTaskIsScheduled() {
        var request = request();
        User user = (User) request.getSession().getAttribute("user");
        user.setPrivilege("user");
        var params = new HashMap<String, Object>(Map.of(
                "sessionId", "session", "vfsPath", "users/other/private.bin", "filePath", "/remote/file.bin"));
        FileCapable node = mock(FileCapable.class);
        try (var utilities = mockStatic(ControllerUtil.class)) {
            utilities.when(() -> ControllerUtil.getCurrentUser(request)).thenReturn(user);
            for (String name : new String[]{"sessionId", "vfsPath", "filePath"}) {
                utilities.when(() -> ControllerUtil.getRequiredStringParam(params, name))
                        .thenReturn((String) params.get(name));
            }
            utilities.when(() -> ControllerUtil.requireCapability(params, FileCapable.class)).thenReturn(node);
            utilities.when(() -> ControllerUtil.getAbstractAgentNode("session")).thenReturn(mock(AbstractAgentNode.class));
            when(files.resolveReadable("owner", "user", "users/other/private.bin"))
                    .thenThrow(new IllegalArgumentException("无权访问该VFS路径"));

            var response = uploadController.start(request, params);

            assertEquals(500, response.get("code"));
            assertEquals("启动上传任务失败: 无权访问该VFS路径", response.get("msg"));
            verify(files).resolveReadable("owner", "user", "users/other/private.bin");
            verifyNoInteractions(uploadService, node);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void progressPassesThroughTheFlatSnapshot(boolean upload) {
        Map<String, Object> snapshot = Map.of("taskId", "task", "state", "PAUSED", "downloadedBytes", 4);
        if (upload) when(uploadService.progress("owner", "task")).thenReturn(snapshot);
        else when(service.progress("owner", "task")).thenReturn(snapshot);
        var response = progress(upload, request(), params());
        assertEquals(200, response.get("code"));
        assertSame(snapshot, response.get("data"));
    }

    @ParameterizedTest
    @CsvSource({"false, 400", "true, 500"})
    void missingTasksRetainTheirExistingErrorStatus(boolean upload, int expectedStatus) {
        var failure = new IllegalArgumentException("任务不存在");
        if (upload) when(uploadService.progress("owner", "task")).thenThrow(failure);
        else when(service.progress("owner", "task")).thenThrow(failure);
        var response = progress(upload, request(), params());
        assertEquals(expectedStatus, response.get("code"));
        assertFalse(response.containsKey("data"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailuresRemainServerErrors(boolean upload) {
        var failure = new IllegalStateException("fixture storage failure");
        if (upload) when(uploadService.progress("owner", "task")).thenThrow(failure);
        else when(service.progress("owner", "task")).thenThrow(failure);
        assertEquals(500, progress(upload, request(), params()).get("code"));
    }

    @ParameterizedTest
    @CsvSource({"false, 400", "true, 500"})
    void progressValidatesLoginBeforeTaskId(boolean upload, int missingIdStatus) {
        var empty = new HashMap<String, Object>();
        assertEquals(401, progress(upload, new MockHttpServletRequest(), empty).get("code"));
        assertEquals(missingIdStatus, progress(upload, request(), empty).get("code"));
        verifyNoInteractions(service, uploadService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void taskListsUseTheSessionIdAndPreserveOwnerAndErrors(boolean upload) {
        var request = request();
        var params = new HashMap<String, Object>(Map.of("sessionId", "session", "taskId", "unrelated"));
        var snapshot = Map.<String, Object>of("sessionId", "session", "tasks", java.util.List.of());
        var failure = new IllegalStateException("fixture storage failure");
        if (upload) when(uploadService.listBySessionId("owner", "session")).thenReturn(snapshot).thenThrow(failure);
        else when(service.listBySessionId("owner", "session")).thenReturn(snapshot).thenThrow(failure);

        assertEquals(401, tasks(upload, new MockHttpServletRequest(), params).get("code"));
        assertEquals(500, tasks(upload, request, new HashMap<>()).get("code"));
        verifyNoInteractions(service, uploadService);
        var response = tasks(upload, request, params);
        assertEquals(200, response.get("code"));
        assertSame(snapshot, response.get("data"));
        var failed = tasks(upload, request, params);
        assertEquals(500, failed.get("code"));
        assertEquals("查询" + (upload ? "上传" : "下载") + "任务列表失败: fixture storage failure", failed.get("msg"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pauseValidatesBeforeCallingTheServiceOrWritingAudit(boolean upload) {
        try (var audit = mockStatic(AuditLogUtil.class)) {
            assertEquals(401, pause(upload, new MockHttpServletRequest(), params()).get("code"));
            assertEquals(500, pause(upload, request(), new HashMap<>()).get("code"));
            verifyNoInteractions(service, uploadService);
            audit.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pausePreservesOwnerSnapshotAndSuccessOnlyAudit(boolean upload) {
        var request = request();
        var params = params();
        var user = (User) request.getSession().getAttribute("user");
        var snapshot = Map.<String, Object>of("state", "PAUSED");
        var failure = new IllegalStateException("fixture storage failure");
        if (upload) when(uploadService.pause("owner", "task")).thenReturn(snapshot).thenThrow(failure);
        else when(service.pause("owner", "task")).thenReturn(snapshot).thenThrow(failure);
        String description = upload ? "暂停上传任务" : "暂停下载任务";
        String operation = upload ? "FILE_UPLOAD_PAUSE" : "FILE_DOWNLOAD_PAUSE";
        try (var audit = mockStatic(AuditLogUtil.class)) {
            audit.when(() -> AuditLogUtil.getClientIp(request)).thenReturn("127.0.0.1");
            var response = pause(upload, request, params);
            assertEquals(200, response.get("code"));
            assertSame(snapshot, response.get("data"));
            audit.verify(() -> AuditLogUtil.logSystemOperation(user, operation, description, "task", params,
                    200, description, "SUCCESS", null, "127.0.0.1", false));
            audit.clearInvocations();
            var failed = pause(upload, request, params);
            assertEquals(500, failed.get("code"));
            assertEquals(description + "失败: fixture storage failure", failed.get("msg"));
            audit.verifyNoInteractions();
        }
    }

    private HashMap<String, Object> progress(boolean upload, MockHttpServletRequest request,
                                              HashMap<String, Object> params) {
        return upload ? uploadController.progress(request, params) : controller.progress(request, params);
    }

    private HashMap<String, Object> pause(boolean upload, MockHttpServletRequest request,
                                           HashMap<String, Object> params) {
        return upload ? uploadController.pause(request, params) : controller.pause(request, params);
    }

    private HashMap<String, Object> tasks(boolean upload, MockHttpServletRequest request,
                                           HashMap<String, Object> params) {
        return upload ? uploadController.tasks(request, params) : controller.tasks(request, params);
    }

    private MockHttpServletRequest request() {
        var user = new User();
        user.setUserId("owner");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", user);
        return request;
    }

    private HashMap<String, Object> params() { return new HashMap<>(Map.of("taskId", "task")); }
}
