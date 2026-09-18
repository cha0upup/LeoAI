package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.assertTransformedRunnable;
import static org.leo.core.component.ComponentTestSupport.code;
import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;
import static org.leo.core.component.ComponentTestSupport.setField;

class RemainingComponentCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void transformedPayloadsRemainRunnableAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("DecompressComponent");
        assertTransformedRunnable("FileUploadComponent");
        assertTransformedRunnable("ScreenComponent");
        assertTransformedRunnable("ResourceComponent");
    }

    @Test
    void decompressAcceptsMixedStringAndByteParameters() throws Exception {
        Path archive = tempDir.resolve("sample.zip");
        writeZip(archive, "nested/value.txt", utf8("zip-ok"));
        Path output = tempDir.resolve("output");
        Files.createDirectories(output.resolve("nested"));
        Files.write(output.resolve("nested/value.txt"), utf8("old-value"));

        Map<String, Object> response = invokeComponent(new DecompressComponent(), params(
                "src", archive.toString(), "des", utf8(output.toString()), "format", utf8("zip")));

        assertEquals(200, code(response));
        assertEquals("zip", response.get("format"));
        assertEquals("zip-ok", Files.readString(output.resolve("nested/value.txt")));
    }

    @Test
    void failedGzipDoesNotDestroyExistingOutput() throws Exception {
        Path archive = tempDir.resolve("invalid.gz");
        Files.write(archive, utf8("not-gzip"));
        Path output = tempDir.resolve("existing.txt");
        Files.write(output, utf8("keep-me"));

        Map<String, Object> response = invokeComponent(new DecompressComponent(), params(
                "src", archive.toString(), "des", output.toString(), "format", "gzip"));

        assertEquals(500, code(response));
        assertEquals("keep-me", Files.readString(output));
    }

    @Test
    void invalidArchiveFormatReturnsClientError() throws Exception {
        Map<String, Object> response = invokeComponent(new DecompressComponent(), params(
                "src", "archive.bin", "des", tempDir.toString(), "format", "unknown"));
        assertEquals(400, code(response));
    }

    @Test
    void uploadAcceptsStringPathAndByteOffset() throws Exception {
        Path output = tempDir.resolve("upload.bin");
        Map<String, Object> first = invokeComponent(new FileUploadComponent(), params(
                "path", output.toString(), "offset", utf8("0"), "data", utf8("first")));
        Map<String, Object> second = invokeComponent(new FileUploadComponent(), params(
                "path", utf8(output.toString()), "offset", "5", "data", utf8("-second")));

        assertEquals(200, code(first));
        assertEquals(200, code(second));
        assertEquals(12L, second.get("nextOffset"));
        assertEquals(12L, second.get("fileLength"));
        assertEquals("first-second", Files.readString(output));
    }

    @Test
    void uploadRejectsInvalidOffsetAndOversizedChunk() throws Exception {
        Map<String, Object> invalidOffset = invokeComponent(new FileUploadComponent(), params(
                "path", tempDir.resolve("invalid.bin").toString(),
                "offset", "not-number", "data", new byte[0]));
        assertEquals(400, code(invalidOffset));

        Map<String, Object> oversized = invokeComponent(new FileUploadComponent(), params(
                "path", tempDir.resolve("large.bin").toString(),
                "offset", 0, "data", new byte[1024 * 1024 + 1]));
        assertEquals(413, code(oversized));
    }

    @Test
    void screenEncodesArgbInputAsJpegAndParsesByteParameters() throws Exception {
        ScreenComponent component = new ScreenComponent();
        setField(component, "params", params("format", utf8("PNG"),
                "quality", utf8("75"), "delay", utf8("250")));

        Method stringParam = ScreenComponent.class.getDeclaredMethod("getStringParam", String.class);
        Method qualityParam = ScreenComponent.class.getDeclaredMethod(
                "getFloatPercentParam", String.class, float.class);
        Method delayParam = ScreenComponent.class.getDeclaredMethod("getIntParam", String.class, int.class);
        stringParam.setAccessible(true);
        qualityParam.setAccessible(true);
        delayParam.setAccessible(true);
        assertEquals("PNG", stringParam.invoke(component, "format"));
        assertEquals(0.75f, (Float) qualityParam.invoke(component, "quality", 0.8f), 0.0001f);
        assertEquals(250, delayParam.invoke(component, "delay", 100));

        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        Method compress = ScreenComponent.class.getDeclaredMethod(
                "compressImage", BufferedImage.class, String.class, float.class);
        compress.setAccessible(true);
        byte[] jpeg = (byte[]) compress.invoke(component, source, "jpg", 0.8f);

        assertTrue(jpeg.length > 0);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(jpeg)));
    }

    @Test
    void resourceAcceptsLeadingSlashBytesAndResetsOversizeState() throws Exception {
        ResourceComponent component = new ResourceComponent();
        Map<String, Object> found = invokeComponent(component, params(
                "resourcePath", utf8("/component/ResourceComponent.payload")));

        assertEquals(200, code(found));
        assertNotNull(found.get("data"));
        assertFalse(found.containsKey("bytecode"));
        assertTrue(((Number) found.get("size")).intValue() > 0);

        setField(component, "resourceTooLarge", true);
        Map<String, Object> missing = invokeComponent(component, params(
                "resourcePath", "component/missing-resource.bin"));
        assertEquals(404, code(missing));
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void writeZip(Path path, String entryName, byte[] data) throws Exception {
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(path.toFile()));
        try {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(data);
            output.closeEntry();
        } finally {
            output.close();
        }
    }
}
