package org.leo.core.agent.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceTest {
    private final CapturingService service = new CapturingService();
    private static final String PATH = "目录/文件🙂.txt";

    @Test
    void usesTextPathsAndBinaryChunksWithoutUnusedOpcodes() throws Exception {
        Map<String, Object> download = service.fileDownloadChunk(PATH, 3, 0);
        assertEquals("FileDownloadComponent", service.component);
        assertEquals(PATH, download.get("path"));
        assertEquals(3L, download.get("size"));
        assertFalse(download.containsKey("op"));
        byte[] bytes = {0, (byte) 255};
        Map<String, Object> upload = service.fileUploadChunk(PATH, 0, bytes);
        assertEquals("FileUploadComponent", service.component);
        assertEquals(PATH, upload.get("path"));
        assertArrayEquals(bytes, (byte[]) upload.get("data"));
        assertFalse(upload.containsKey("op"));
        Map<String, Object> created = service.createFile(PATH, "内容");
        assertEquals("createFile", created.get("action"));
        assertArrayEquals("内容".getBytes(StandardCharsets.UTF_8), (byte[]) created.get("content"));
        assertEquals("list", service.getFileList(PATH).get("action"));
        assertThrows(NullPointerException.class, () -> service.deleteFile(null));
    }

    @Test
    void preservesOptionalFieldAndConflictStrategySemantics() throws Exception {
        Map<String, Object> compressed = service.compress(PATH, "archive.zip", " .*\\.tmp ");
        assertEquals(PATH, compressed.get("src"));
        assertEquals("archive.zip", compressed.get("des"));
        assertEquals(".*\\.tmp", compressed.get("exclude"));
        assertFalse(service.compress(PATH, "archive.zip", " \t ").containsKey("exclude"));
        assertEquals("zip", service.decompress("archive.zip", "output", " zip ").get("format"));
        Map<String, Object> copy = service.copyFile(PATH, "dest", " overwrite ");
        assertEquals("copy", copy.get("action"));
        assertEquals(" overwrite ", copy.get("conflictStrategy"));
        assertEquals("dest", copy.get("destPath"));
        assertEquals("dest", service.moveFile(PATH, "dest", "skip").get("newPath"));
    }

    private static final class CapturingService extends FileService {
        private String component;
        CapturingService() { super(null, List.of(), List.of()); }
        @Override public Map<String, Object> invokeComponent(String component, Map<String, Object> params) {
            this.component = component;
            return params;
        }
    }
}
