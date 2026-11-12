package org.leo.ai.service.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillFileServiceTest {
    @TempDir Path directory;
    private final SkillFileService files = new SkillFileService();

    @Test
    void rejectsNulPathsBeforeReadingWritingDeletingOrMovingFiles() throws Exception {
        Files.writeString(directory.resolve("keep.txt"), "fixture");
        String invalid = "bad\0name.txt";
        List<Executable> operations = List.of(
                () -> files.readFile(directory, invalid),
                () -> files.writeFile(directory, invalid, "fixture", "text"),
                () -> files.deleteFile(directory, invalid),
                () -> files.moveFile(directory, invalid, "moved.txt"),
                () -> files.moveFile(directory, "keep.txt", invalid));
        for (Executable operation : operations) {
            var error = assertThrows(SkillFileService.SkillFileException.class, operation);
            assertEquals("path 含非法字符", error.getMessage());
        }
        assertEquals("fixture", Files.readString(directory.resolve("keep.txt")));
        assertEquals(1, files.listFiles(directory).size());
    }

    @Test
    void retainsNestedFileCrudAndBinaryContent() throws Exception {
        files.writeFile(directory, "notes/readme.txt", "文本 fixture", "text");
        assertEquals("文本 fixture", files.readFile(directory, "notes/readme.txt").get("content"));
        files.moveFile(directory, "notes/readme.txt", "notes/renamed.txt");
        assertFalse(Files.exists(directory.resolve("notes/readme.txt")));
        files.deleteFile(directory, "notes/renamed.txt");
        assertTrue(files.listFiles(directory).isEmpty());

        byte[] binary = {65, 0, 66};
        files.writeFile(directory, "notes/binary.txt", Base64.getEncoder().encodeToString(binary), "base64");
        var read = files.readFile(directory, "notes/binary.txt");
        assertEquals("base64", read.get("encoding"));
        assertArrayEquals(binary, Base64.getDecoder().decode((String) read.get("content")));
    }

    @Test
    void countsHiddenFilesTowardTheLimitButStillAllowsReplacingExistingFiles() throws Exception {
        for (int i = 0; i < SkillFileService.MAX_FILES; i++) {
            Files.createFile(directory.resolve(i == 0 ? ".hidden" : "file-" + i));
        }
        assertThrows(SkillFileService.SkillFileException.class,
                () -> files.writeFile(directory, "extra.txt", "new", "text"));
        assertFalse(Files.exists(directory.resolve("extra.txt")));
        files.writeFile(directory, "file-1", "updated", "text");
        assertEquals("updated", Files.readString(directory.resolve("file-1")));
    }

    @Test
    void subtractsReplacedBytesFromTheDirectoryBudget() throws Exception {
        for (int i = 0; i < SkillFileService.MAX_SKILL_BYTES / SkillFileService.MAX_FILE_BYTES; i++) {
            try (var file = new RandomAccessFile(directory.resolve("part-" + i).toFile(), "rw")) {
                file.setLength(SkillFileService.MAX_FILE_BYTES);
            }
        }
        assertThrows(SkillFileService.SkillFileException.class,
                () -> files.writeFile(directory, "extra.txt", "new", "text"));
        assertFalse(Files.exists(directory.resolve("extra.txt")));
        files.writeFile(directory, "part-0", "smaller", "text");
        files.writeFile(directory, "extra.txt", "new", "text");
        assertEquals("smaller", Files.readString(directory.resolve("part-0")));
        assertEquals("new", Files.readString(directory.resolve("extra.txt")));
    }
}
