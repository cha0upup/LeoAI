package org.leo.web.controller.platform.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.service.config.SystemConfigService;
import org.leo.web.service.UserArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserFileControllerTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final UserArtifactService artifacts = new UserArtifactService(new ObjectMapper(), systemConfigService);
    private final UserFileController controller = new UserFileController(artifacts);
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

    @Test
    @SuppressWarnings("unchecked")
    void countsEveryCategoryInOneVisibleWorkspaceWithoutRuntimeOrInternalFiles() throws Exception {
        var request = authenticatedRequest();
        controller.createFile(request, "script-builds/a/b/c/d/e/f/g/h/build.txt", "build", false);
        controller.createFile(request, "ai-reports/report.md", "report", false);
        controller.createFile(request, "legacy/note.txt", "note", false);
        Path runtime = artifacts.root(userId).resolve("ai-tasks/job/.internal/versions/cache.txt");
        Files.createDirectories(runtime.getParent());
        Files.writeString(runtime, "runtime cache");
        var overview = (Map<String, Object>) controller.overview(request).get("data");
        assertEquals(3L, overview.get("totalFiles"));
        assertEquals(15L, overview.get("totalBytes"));
        var categories = (List<Map<String, Object>>) overview.get("categories");
        assertEquals(List.of(1L, 1L, 0L, 1L), categories.stream().map(row -> row.get("files")).toList());
        assertEquals(3, ((List<?>) overview.get("recentFiles")).size());
        var page = artifacts.list(userId, "", "", "", 1, 2);
        assertEquals(3, page.get("count"));
        assertEquals(2, ((List<?>) page.get("fileList")).size());
        assertEquals(true, page.get("hasMore"));
        assertEquals(1, ((List<?>) artifacts.list(userId, "", "", "", 2, 2).get("fileList")).size());
        assertEquals(1, artifacts.list(userId, "script-builds", "", "", 1, 50).get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsProvenanceAndFiltersAcrossDirectoriesWithoutSharingUserData() throws Exception {
        var saved = artifacts.archive(userId, new UserArtifactService.ArchiveRequest("ai-reports", "report.md", "# Report", null,
                Map.of("title", "Database review", "sourceModule", "node-ai", "sourceId", "thread-a", "hostName", "db-host")));
        var reopened = new UserArtifactService(new ObjectMapper(), systemConfigService);
        var page = reopened.list(userId, "ai-reports", " DB-HOST ", "node-ai", 1, 50);
        var file = ((List<Map<String, Object>>) page.get("fileList")).get(0);
        var metadata = (Map<String, Object>) file.get("metadata");
        assertEquals(saved.get("path"), file.get("path"));
        assertEquals("thread-a", metadata.get("sourceId"));
        assertTrue(metadata.containsKey("createdAt"));
        assertTrue(metadata.containsKey("id"));
        assertEquals(0, reopened.list(userId, "", "", "platform-ai", 1, 50).get("count"));
        assertThrows(IllegalArgumentException.class, () -> reopened.resolve(userId, "../../other-user/workspace/" + saved.get("path"), false));
        controller.createFile(authenticatedRequest(), String.valueOf(saved.get("path")), "manual revision", true);
        assertEquals(0, reopened.list(userId, "", "", "node-ai", 1, 50).get("count"));
        assertEquals(1, reopened.list(userId, "", "", "manual", 1, 50).get("count"));
        assertEquals(200, controller.delete(authenticatedRequest(), String.valueOf(saved.get("path")), false).get("code"));
        try (var files = Files.list(artifacts.root(userId).resolve(".artifacts"))) { assertEquals(0, files.count()); }
    }

    @Test
    void archivesTaskOutputAsAnIndependentCopyAndKeepsTheOriginal() throws Exception {
        String source = "ai-tasks/job/files/output.txt";
        Path original = artifacts.root(userId).resolve(source);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "task result");
        var saved = artifacts.archive(userId, new UserArtifactService.ArchiveRequest("task-results", "output.txt", null, source, Map.of()));
        assertEquals("task result", Files.readString(artifacts.root(userId).resolve(String.valueOf(saved.get("path")))));
        assertEquals("task result", Files.readString(original));
        assertEquals(1, artifacts.list(userId, "task-results", "", "task-output", 1, 50).get("count"));
        assertEquals(200, controller.preview(authenticatedRequest(), source, null).get("code"));
        assertThrows(IllegalArgumentException.class, () -> artifacts.archive(userId,
                new UserArtifactService.ArchiveRequest("task-results", "private.txt", null, "ai-tasks/job/.internal/private.txt", Map.of())));
    }

    @Test
    void blocksRuntimeWritesRootDeletionAndLinkedPaths() throws Exception {
        var request = authenticatedRequest();
        assertEquals(400, controller.createFile(request, "ai-tasks/job/files/a.txt", "changed", true).get("code"));
        assertEquals(400, controller.createDir(request, ".artifacts").get("code"));
        assertEquals(400, controller.delete(request, "", true).get("code"));
        assertEquals(400, controller.list(request, "ai-tasks", false, 1, 50, "", "").get("code"));
        Path actual = artifacts.root(userId).resolve("original.txt");
        Files.writeString(actual, "content");
        Files.createSymbolicLink(artifacts.root(userId).resolve("link.txt"), actual);
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve(userId, "link.txt", false));
        assertEquals(1, artifacts.list(userId, "", "", "", 1, 50).get("count"));
        assertEquals(401, controller.archive(new MockHttpServletRequest(), null).get("code"));
    }

    @Test
    void rollsBackAnArchiveIfItsMetadataIsInvalid() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> artifacts.archive(userId,
                new UserArtifactService.ArchiveRequest("ai-reports", "report.md", "report", null, Map.of("sourceModule", "invalid"))));
        assertEquals(0, artifacts.list(userId, "", "", "", 1, 50).get("count"));
        when(systemConfigService.getString("max.file.upload.size.mb", "100")).thenReturn("1");
        assertThrows(IllegalArgumentException.class, () -> artifacts.archive(userId,
                new UserArtifactService.ArchiveRequest("imports", "large.txt", "x".repeat(1024 * 1024 + 1), null, Map.of())));
        assertEquals(0, artifacts.list(userId, "", "", "", 1, 50).get("count"));
    }

    @Test
    void bindsTheArchiveAndPaginatedListHttpContracts() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var session = (org.springframework.mock.web.MockHttpSession) authenticatedRequest().getSession();
        mvc.perform(post("/platform/user/file/archive").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ai-reports\",\"filename\":\"report.md\",\"content\":\"# Review\","
                                + "\"metadata\":{\"title\":\"Review\",\"sourceModule\":\"platform-ai\",\"sourceId\":\"thread-a\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.metadata.sourceId").value("thread-a"));
        mvc.perform(get("/platform/user/file/list").session(session).param("recursive", "true")
                        .param("page", "1").param("pageSize", "1").param("source", "platform-ai").param("search", "review"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.count").value(1)).andExpect(jsonPath("$.data.fileList[0].metadata.title").value("Review"));
    }

    @Test
    void downloadsWorkspaceFilesWithTheSharedResponseWriter() throws Exception {
        controller.createFile(authenticatedRequest(), "notes/file.txt", "hello 世界", false);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        controller.download(authenticatedRequest(), "notes/file.txt", "中文 报告.txt", response);
        assertEquals(200, response.getStatus());
        assertEquals("hello 世界", new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("中文 报告.txt", org.springframework.http.ContentDisposition
                .parse(response.getHeader("Content-Disposition")).getFilename());
    }

    @Test
    void workspaceDownloadsDoNotReachSiblingTaskStorage() throws Exception {
        Path downloads = artifacts.root(userId).getParent().resolve("downloads");
        Files.createDirectories(downloads);
        Files.writeString(downloads.resolve("fixture.txt"), "private task data");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        controller.download(authenticatedRequest(), "../downloads/fixture.txt", null, response);
        assertEquals(400, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void workspaceDownloadsStillRequireLogin() {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        controller.download(new MockHttpServletRequest(), "notes/file.txt", null, response);
        assertEquals(401, response.getStatus());
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
