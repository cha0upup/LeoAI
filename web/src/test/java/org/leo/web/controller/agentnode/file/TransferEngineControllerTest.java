package org.leo.web.controller.agentnode.file;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.service.transfer.DownloadEngineService;
import org.leo.service.transfer.UploadEngineService;
import org.leo.web.util.AuditLogUtil;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferEngineControllerTest {
    private final DownloadEngineService service = mock(DownloadEngineService.class);
    private final DownloadEngineController controller = new DownloadEngineController(service);
    private final UploadEngineService uploadService = mock(UploadEngineService.class);
    private final UploadEngineController uploadController = new UploadEngineController(uploadService);

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

    private MockHttpServletRequest request() {
        var user = new User();
        user.setUserId("owner");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", user);
        return request;
    }

    private HashMap<String, Object> params() { return new HashMap<>(Map.of("taskId", "task")); }
}
