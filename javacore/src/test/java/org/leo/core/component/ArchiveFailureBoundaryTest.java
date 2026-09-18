package org.leo.core.component;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.NewExpr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentParameterBoundaryTest.*;

public class ArchiveFailureBoundaryTest {
    @TempDir Path directory;
    private static final List<TrackingInput> inputs = new ArrayList<>();

    @AfterEach
    void closeTrackedFiles() throws Exception {
        for (TrackingInput input : inputs) input.close();
        inputs.clear();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedOutputCloseKeepsOriginalFileAndDeletesTemporaryFile(boolean payload) throws Exception {
        for (String format : List.of("zip", "gzip", "tar", "tar.gz")) {
            Path source = archive(format);
            Path output = Files.createDirectory(directory.resolve("output-" + format));
            Path original = Files.writeString(output.resolve("file.txt"), "original");
            Object component = instrumented(payload, false);
            Map<String, Object> result = prepare(component, Map.of("src", source.toString(),
                    "des", (format.equals("gzip") ? original : output).toString(), "format", format));
            call(component, "invoke", new Class[0]);
            assertEquals(500, result.get("code"), format);
            assertTrue(String.valueOf(result.get("msg")).contains("injected close failure"), format);
            assertEquals("original", Files.readString(original), format);
            try (var files = Files.list(output)) {
                assertEquals(List.of(original), files.toList(), format);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidGzipHeaderClosesUnderlyingInput(boolean payload) throws Exception {
        Path source = Files.writeString(directory.resolve("bad.gz"), "invalid gzip header");
        Path destination = directory.resolve("result.txt");
        Object component = instrumented(payload, true);
        Map<String, Object> result = prepare(component, Map.of("src", source.toString(),
                "des", destination.toString(), "format", "gzip"));
        call(component, "invoke", new Class[0]);
        assertEquals(500, result.get("code"));
        assertEquals(1, inputs.size());
        assertTrue(inputs.get(0).closed, "GZIP constructor failure must close the raw file stream");
        assertFalse(Files.exists(destination));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(source), files.toList());
        }
    }

    private Object instrumented(boolean payload, final boolean trackInput) throws Exception {
        String resource = payload ? "/component/DecompressComponent.payload"
                : "/org/leo/core/component/DecompressComponent.class";
        ClassPool pool = new ClassPool(true);
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            CtClass type = pool.makeClass(input);
            try {
                type.instrument(new ExprEditor() {
                    public void edit(NewExpr expression) throws CannotCompileException {
                        if (trackInput && expression.getClassName().equals("java.io.FileInputStream")) {
                            expression.replace("{ $_ = org.leo.core.component.ArchiveFailureBoundaryTest.openInput($1); }");
                        } else if (!trackInput && expression.getClassName().equals("java.io.FileOutputStream")) {
                            expression.replace("{ $_ = org.leo.core.component.ArchiveFailureBoundaryTest.openOutput($1); }");
                        }
                    }
                });
                return new BytecodeLoader().load(type.toBytecode()).getDeclaredConstructor().newInstance();
            } finally {
                type.detach();
            }
        }
    }

    public static FileInputStream openInput(Object file) throws IOException {
        TrackingInput input = new TrackingInput(file instanceof File ? (File) file : new File(String.valueOf(file)));
        inputs.add(input);
        return input;
    }

    public static FileOutputStream openOutput(File file) throws IOException {
        return new FileOutputStream(file) {
            public void close() throws IOException {
                super.close();
                throw new IOException("injected close failure");
            }
        };
    }

    private static class TrackingInput extends FileInputStream {
        boolean closed;
        TrackingInput(File file) throws IOException { super(file); }
        public void close() throws IOException { closed = true; super.close(); }
    }

    private Path archive(String format) throws Exception {
        Path path = directory.resolve("input." + format);
        byte[] content = "new".getBytes(StandardCharsets.UTF_8);
        if (format.equals("zip")) {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
                output.putNextEntry(new ZipEntry("file.txt"));
                output.write(content);
                output.closeEntry();
            }
        } else if (format.equals("gzip")) {
            try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
                output.write(content);
            }
        } else {
            byte[] tar = new byte[2048];
            put(tar, 0, "file.txt");
            put(tar, 100, "0000644");
            put(tar, 124, "00000000003");
            for (int i = 148; i < 156; i++) tar[i] = ' ';
            tar[156] = '0';
            int checksum = 0;
            for (int i = 0; i < 512; i++) checksum += tar[i] & 0xff;
            put(tar, 148, String.format("%06o\0 ", checksum));
            System.arraycopy(content, 0, tar, 512, content.length);
            if (format.equals("tar.gz")) {
                try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) { output.write(tar); }
            } else {
                Files.write(path, tar);
            }
        }
        return path;
    }

    private void put(byte[] data, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }
}
