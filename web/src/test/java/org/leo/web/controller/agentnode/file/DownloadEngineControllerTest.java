package org.leo.web.controller.agentnode.file;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.service.DownloadEngineService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadEngineControllerTest {
    private final DownloadEngineService service = mock(DownloadEngineService.class);
    private final DownloadEngineController controller = new DownloadEngineController(service);

    @Test
    void progressPassesThroughTheFlatSnapshot() {
        Map<String, Object> snapshot = Map.of("taskId", "task", "state", "PAUSED", "downloadedBytes", 4);
        when(service.progress("owner", "task")).thenReturn(snapshot);
        var response = controller.progress(request(), params());
        assertEquals(200, response.get("code"));
        assertSame(snapshot, response.get("data"));
    }

    @Test
    void missingTasksAreRequestErrorsRatherThanFakeProgress() {
        when(service.progress("owner", "task")).thenThrow(new IllegalArgumentException("任务不存在"));
        var response = controller.progress(request(), params());
        assertEquals(400, response.get("code"));
        assertFalse(response.containsKey("data"));
    }

    @Test
    void storageFailuresRemainServerErrors() {
        when(service.progress("owner", "task")).thenThrow(new IllegalStateException("fixture storage failure"));
        assertEquals(500, controller.progress(request(), params()).get("code"));
    }

    @Test
    void progressRequiresLogin() {
        assertEquals(401, controller.progress(new MockHttpServletRequest(), params()).get("code"));
        verifyNoInteractions(service);
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
