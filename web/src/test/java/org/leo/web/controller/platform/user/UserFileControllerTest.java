package org.leo.web.controller.platform.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.service.config.SystemConfigService;
import org.leo.web.application.artifact.UserArtifactService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid-session-user"})
    void rejectsMissingOrMalformedSessionIdentity(String cachedUser) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", cachedUser);
        assertEquals(401, controller.overview(request).get("code"));
    }

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
    void requiresExplicitOverwriteAndListsTheReplacementMetadata() throws Exception {
        MockHttpServletRequest request = authenticatedRequest();

        var created = controller.createFile(request, "notes/report.md", "first", false);
        var conflict = controller.createFile(request, "notes/report.md", "second", false);
        var overwritten = controller.createFile(request, "notes/report.md", "second", true);

        assertEquals(200, created.get("code"));
        assertEquals(409, conflict.get("code"));
        assertEquals(200, overwritten.get("code"));
        Path file = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId)
                .toPath().toAbsolutePath().normalize().resolve("notes/report.md");
        assertEquals("second", Files.readString(file));
        long modified = Files.getLastModifiedTime(file).toMillis();
        try (var reads = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            var listing = (Map<?, ?>) controller.list(request, "notes", false, 1, 50, "", "").get("data");
            var items = (List<?>) listing.get("fileList");
            assertEquals(List.of(Map.of("name", "report.md", "path", "notes/report.md", "parentPath", "notes",
                    "isDirectory", false, "size", 6L, "lastModified", modified)), items);
            reads.verify(() -> Files.readAttributes(file, BasicFileAttributes.class));
            reads.when(() -> Files.readAttributes(file, BasicFileAttributes.class))
                    .thenThrow(new java.io.IOException("fixture attributes unavailable"));
            var fallback = (Map<?, ?>) controller.list(request, "notes", false, 1, 50, "", "").get("data");
            assertEquals(List.of(Map.of("name", "report.md", "path", "notes/report.md", "parentPath", "notes",
                    "isDirectory", false, "size", 0L, "lastModified", 0L)), fallback.get("fileList"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    @ResourceLock("java.util.Locale.default")
    @SuppressWarnings("unchecked")
    void countsAndListsVisibleWorkspaceWithoutRuntimeOrInternalFiles(String languageTag) throws Exception {
        var request = authenticatedRequest();
        controller.createFile(request, "script-builds/a/b/c/d/e/f/g/h/build.txt", "build", false);
        controller.createFile(request, "ai-reports/report.md", "report", false);
        controller.createFile(request, "Imports/note.txt", "note", false);
        Path runtime = artifacts.root(userId).resolve("ai-tasks/job/.internal/versions/cache.txt");
        Files.createDirectories(runtime.getParent());
        Files.writeString(runtime, "runtime cache");
        var overview = (Map<String, Object>) controller.overview(request).get("data");
        assertEquals(3L, overview.get("totalFiles"));
        assertEquals(15L, overview.get("totalBytes"));
        var categories = (List<Map<String, Object>>) overview.get("categories");
        assertEquals(List.of(1L, 1L, 0L, 1L), categories.stream().map(row -> row.get("files")).toList());
        assertEquals(3, ((List<?>) overview.get("recentFiles")).size());
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            var directory = (Map<String, Object>) controller.list(request, "", false, 1, 50, "", "").get("data");
            var items = (List<Map<String, Object>>) directory.get("fileList");
            assertEquals(List.of("ai-reports", "Imports", "script-builds"),
                    items.stream().map(item -> item.get("name")).toList());
        } finally {
            Locale.setDefault(previous);
        }
        var listing = spy(artifacts);
        var page = listing.list(userId, "", "", "", 1, 2);
        verify(listing).root(userId);
        assertEquals(3, page.get("count"));
        assertEquals(2, ((List<?>) page.get("fileList")).size());
        assertEquals(true, page.get("hasMore"));
        assertEquals(1, ((List<?>) artifacts.list(userId, "", "", "", 2, 2).get("fileList")).size());
        assertEquals(1, artifacts.list(userId, "script-builds", "", "", 1, 50).get("count"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void persistsProvenanceAndCleansMetadataOnDeletion(boolean recursive) throws Exception {
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
        if (recursive) {
            controller.createFile(authenticatedRequest(), "ai-reports/nested/extra.txt", "extra", false);
            assertEquals(400, controller.delete(authenticatedRequest(), "ai-reports", false).get("code"));
        }
        String target = recursive ? "ai-reports" : String.valueOf(saved.get("path"));
        assertEquals(Map.of("code", 200, "msg", "删除成功",
                        "data", Map.of("path", target, "deleted", true, "recursive", recursive)),
                controller.delete(authenticatedRequest(), target, recursive));
        assertTrue(Files.notExists(artifacts.root(userId).resolve(target)));
        try (var files = Files.list(artifacts.root(userId).resolve(".artifacts"))) { assertEquals(0, files.count()); }
        assertEquals(Map.of("code", 404, "msg", "文件或目录不存在"),
                controller.delete(authenticatedRequest(), target, recursive));
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
        assertEquals(401, controller.delete(new MockHttpServletRequest(), "original.txt", false).get("code"));
        assertEquals(400, controller.list(request, "ai-tasks", false, 1, 50, "", "").get("code"));
        Path actual = artifacts.root(userId).resolve("original.txt");
        Files.writeString(actual, "content");
        Files.createSymbolicLink(artifacts.root(userId).resolve("link.txt"), actual);
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve(userId, "link.txt", false));
        assertEquals(1, artifacts.list(userId, "", "", "", 1, 50).get("count"));
        var directory = (Map<?, ?>) controller.list(request, "", false, 1, 50, "", "").get("data");
        assertEquals(1, directory.get("count"));
        assertEquals(Map.of("code", 404, "msg", "目录不存在"),
                controller.list(request, "missing", false, 1, 50, "", ""));
        assertEquals(Map.of("code", 400, "msg", "path不是目录"),
                controller.list(request, "original.txt", false, 1, 50, "", ""));
        assertEquals(401, controller.list(new MockHttpServletRequest(), "", false, 1, 50, "", "").get("code"));
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

    @Test
    @SuppressWarnings("unchecked")
    void previewKeepsFileLimitsAndPathAccessChecks() throws Exception {
        var request = authenticatedRequest();
        controller.createFile(request, "notes/preview.txt", "hello fixture", false);

        var result = controller.preview(request, "notes/preview.txt", 5L);
        assertEquals(200, result.get("code"));
        var preview = (Map<String, Object>) result.get("data");
        assertEquals("hello", preview.get("content"));
        assertEquals(5, preview.get("previewSize"));
        assertEquals(true, preview.get("truncated"));
        assertEquals("notes/preview.txt", preview.get("path"));
        var complete = (Map<String, Object>) controller.preview(request, "notes/preview.txt", null).get("data");
        assertEquals("hello fixture", complete.get("content"));
        assertEquals(13, complete.get("previewSize"));
        assertEquals(false, complete.get("truncated"));
        var minimum = (Map<String, Object>) controller.preview(request, "notes/preview.txt", 0L).get("data");
        assertEquals(1, minimum.get("previewSize"));
        assertEquals("h", minimum.get("content"));

        byte[] binary = new byte[1024 * 1024 + 17];
        Arrays.fill(binary, (byte) 0xff);
        Files.write(artifacts.root(userId).resolve("notes/binary.bin"), binary);
        var maximum = (Map<String, Object>) controller.preview(request, "notes/binary.bin", Long.MAX_VALUE).get("data");
        assertEquals((long) binary.length, maximum.get("size"));
        assertEquals(1024 * 1024, maximum.get("previewSize"));
        assertEquals(true, maximum.get("truncated"));
        assertArrayEquals(Arrays.copyOf(binary, 1024 * 1024), Base64.getDecoder().decode((String) maximum.get("contentBase64")));
        var defaultSize = (Map<String, Object>) controller.preview(request, "notes/binary.bin", null).get("data");
        assertEquals(64 * 1024, defaultSize.get("previewSize"));

        controller.createFile(request, "notes/empty.txt", "", false);
        var empty = (Map<String, Object>) controller.preview(request, "notes/empty.txt", null).get("data");
        assertEquals(0, empty.get("previewSize"));
        assertEquals("", empty.get("contentBase64"));
        assertEquals(404, controller.preview(request, "notes", null).get("code"));
        assertEquals(400, controller.preview(request, "../private.txt", null).get("code"));
        assertEquals(401, controller.preview(new MockHttpServletRequest(), "notes/preview.txt", null).get("code"));

        var userWithoutId = new MockHttpServletRequest();
        userWithoutId.getSession().setAttribute("user", new User());
        assertEquals(Map.of("code", 400, "msg", "用户信息无效"),
                controller.preview(userWithoutId, "notes/preview.txt", null));
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
