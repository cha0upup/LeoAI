package org.leo.web.config;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.repository.session.AtomicFileStore;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class GlobalExceptionHandlerFileStoreTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailuresReturnAnExplicitErrorWithoutExposingPathsOrContents(boolean corrupt) throws Exception {
        Path target = temp.resolve("fixture.json");
        if (corrupt) Files.writeString(target, "{private-content");
        else Files.createDirectory(target);
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController(target))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/storage-fixture")).andReturn().getResponse();
        assertEquals(500, response.getStatus());
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains(corrupt ? "INVALID_CONTENT" : "READ_FAILED"), body);
        assertTrue(body.contains("traceId"));
        assertTrue(body.contains("fixture.json"));
        assertFalse(body.contains(temp.toString()));
        assertFalse(body.contains("private-content"));
    }

    @RestController
    static class FixtureController {
        private final Path path;
        FixtureController(Path path) { this.path = path; }
        @GetMapping("/storage-fixture")
        public Object read() { return new AtomicFileStore().readJsonMap(path.toFile()); }
    }
}
