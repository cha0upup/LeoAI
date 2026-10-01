package org.leo.web.controller.platform.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.service.config.SystemConfigService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class UserFileControllerTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final UserFileController controller = new UserFileController(systemConfigService);
    private final String userId = "test-user-file-" + UUID.randomUUID();

    @AfterEach
    void cleanWorkspace() throws Exception {
        Path userDir = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId)
                .toPath().toAbsolutePath().normalize().getParent();
        if (userDir == null || !Files.exists(userDir)) return;
        try (var paths = Files.walk(userDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup for an isolated test directory.
                }
            });
        }
    }

    @Test
    void requiresExplicitOverwriteBeforeReplacingExistingDocument() throws Exception {
        MockHttpServletRequest request = authenticatedRequest();

        var created = controller.createFile(request, "notes/report.md", "first", false);
        var conflict = controller.createFile(request, "notes/report.md", "second", false);
        var overwritten = controller.createFile(request, "notes/report.md", "second", true);

        assertEquals(200, created.get("code"));
        assertEquals(409, conflict.get("code"));
        assertEquals(200, overwritten.get("code"));
        Path file = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId)
                .toPath().resolve("notes/report.md");
        assertEquals("second", Files.readString(file));
    }

    private MockHttpServletRequest authenticatedRequest() {
        User user = new User();
        user.setUserId(userId);
        user.setUserName("tester");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", user);
        return request;
    }
}
