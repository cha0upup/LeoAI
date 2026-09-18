package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.assertTransformedRunnable;
import static org.leo.core.component.ComponentTestSupport.code;
import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;
import static org.leo.core.component.ComponentTestSupport.setField;

class InformationAndFileEnhanceComponentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void transformedPayloadsInitializeAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("BasicInfoComponent");
        assertTransformedRunnable("CredentialHarvestComponent");
        assertTransformedRunnable("FileEnhanceComponent");
    }

    @Test
    void basicInfoCollectsStableResponseWithoutKeepingRequestState() throws Exception {
        BasicInfoComponent component = new BasicInfoComponent();
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "results", results);

        component.invoke();

        assertEquals(200, ((Number) results.get("code")).intValue());
        Map<?, ?> basicInfo = (Map<?, ?>) results.get("BasicInfo");
        assertNotNull(basicInfo);
        assertTrue(basicInfo.containsKey("OSInfo"));
        assertTrue(basicInfo.containsKey("JavaRuntimeInfo"));
        assertTrue(basicInfo.containsKey("NetworkInfo"));
    }

    @Test
    void basicInfoProvidesJavaFirstSystemSnapshots() throws Exception {
        Map<String, Object> processes = invokeComponent(new BasicInfoComponent(), params("action", "processes"));
        assertEquals(200, code(processes));
        assertTrue(((List<?>) processes.get("processes")).size() > 0);

        Map<String, Object> disks = invokeComponent(new BasicInfoComponent(), params("action", "disks"));
        assertEquals(200, code(disks));
        assertTrue(((List<?>) disks.get("disks")).size() > 0);
        List<?> diskList = (List<?>) disks.get("disks");
        Map<?, ?> disk = (Map<?, ?>) diskList.get(0);
        assertTrue(disk.containsKey("mount"));
        assertTrue(disk.containsKey("totalBytes"));
        assertTrue(disk.containsKey("freeBytes"));
        assertFalse(disk.containsKey("TotalSpaceMB"));
        assertFalse(disk.containsKey("usedBytes"));
        boolean hasCapacity = false;
        for (int i = 0; i < diskList.size(); i++) {
            Map<?, ?> item = (Map<?, ?>) diskList.get(i);
            if (((Number) item.get("totalBytes")).longValue() > 0L) hasCapacity = true;
        }
        assertTrue(hasCapacity);

        Map<String, Object> network = invokeComponent(new BasicInfoComponent(), params("action", "network"));
        assertEquals(200, code(network));
        assertNotNull(network.get("interfaces"));
    }

    @Test
    void credentialPropertyFilterIsCaseInsensitiveAndInvalidOperationIsRejected() throws Exception {
        String key = "leo.component.test.custom.setting";
        String previous = System.getProperty(key);
        System.setProperty(key, "component-value");
        try {
            Map<String, Object> response = invokeComponent(new CredentialHarvestComponent(), params(
                    "op", 2, "filter", "COMPONENT.TEST.CUSTOM"));
            assertEquals(200, code(response));
            Map<?, ?> credentials = (Map<?, ?>) response.get("credentials");
            List<?> properties = (List<?>) credentials.get("systemProperties");
            assertTrue(properties.stream().anyMatch(item -> key.equals(((Map<?, ?>) item).get("key"))));

            Map<String, Object> invalid = invokeComponent(new CredentialHarvestComponent(), params("op", 99));
            assertEquals(400, code(invalid));
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    void fileEnhanceAcceptsByteParametersAndBuildsReadableTarArchive() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("sample.txt"), "alpha component line\n", StandardCharsets.UTF_8);

        Map<String, Object> grep = invokeComponent(new FileEnhanceComponent(), params(
                "action", 1,
                "path", source.toString().getBytes(StandardCharsets.UTF_8),
                "keyword", "component".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, code(grep));
        assertEquals(1, ((Number) grep.get("matchCount")).intValue());

        Map<String, Object> packed = invokeComponent(new FileEnhanceComponent(), params(
                "action", 3, "path", source.toString(), "destPath", source.toString()));
        assertEquals(200, code(packed));
        File archive = new File(String.valueOf(packed.get("archivePath")));
        assertTrue(archive.isFile());

        List<String> entries = readTarEntries(archive);
        assertTrue(entries.contains("source/"), entries.toString());
        assertTrue(entries.contains("source/sample.txt"), entries.toString());
        assertFalse(entries.contains("source/" + packed.get("archiveName")), entries.toString());
    }

    @Test
    void chmodAcceptsAllZeroMode() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("mode.txt"), "mode", StandardCharsets.UTF_8);
        try {
            Map<String, Object> response = invokeComponent(new FileEnhanceComponent(), params(
                    "action", 5, "path", file.toString(), "mode", "0000"));
            assertEquals(200, code(response));
            assertEquals("0", response.get("mode"));
        } finally {
            File target = file.toFile();
            target.setReadable(true, true);
            target.setWritable(true, true);
        }
    }

    @Test
    void failedPackDeletesPartialArchive() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("long-entry-source"));
        Files.writeString(source.resolve("x".repeat(101)), "content", StandardCharsets.UTF_8);
        Path destination = Files.createDirectory(temporaryDirectory.resolve("archives"));

        assertThrows(InvocationTargetException.class, () -> invokeComponent(new FileEnhanceComponent(), params(
                "action", 3, "path", source.toString(), "destPath", destination.toString())));
        try (java.util.stream.Stream<Path> files = Files.list(destination)) {
            assertEquals(0L, files.count());
        }
    }

    private List<String> readTarEntries(File archive) throws Exception {
        ArrayList<String> entries = new ArrayList<>();
        GZIPInputStream input = new GZIPInputStream(new FileInputStream(archive));
        try {
            byte[] header = new byte[512];
            while (readFully(input, header) == header.length && !allZero(header)) {
                entries.add(readString(header, 0, 100));
                long size = Long.parseLong(readString(header, 124, 12).trim(), 8);
                long padded = ((size + 511L) / 512L) * 512L;
                while (padded > 0) {
                    long skipped = input.skip(padded);
                    if (skipped <= 0) {
                        if (input.read() < 0) break;
                        skipped = 1;
                    }
                    padded -= skipped;
                }
            }
        } finally {
            input.close();
        }
        return entries;
    }

    private int readFully(GZIPInputStream input, byte[] target) throws Exception {
        int offset = 0;
        while (offset < target.length) {
            int length = input.read(target, offset, target.length - offset);
            if (length < 0) break;
            offset += length;
        }
        return offset;
    }

    private String readString(byte[] value, int offset, int length) {
        int end = offset;
        while (end < offset + length && value[end] != 0) end++;
        return new String(value, offset, end - offset, StandardCharsets.UTF_8);
    }

    private boolean allZero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
}
