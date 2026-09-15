package org.leo.jmg.mem.packer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackerScannerTest {

    @Test
    void resolvesClassNameFromClasspathResourcePath() {
        Resource resource = new FileSystemResource(
                "/tmp/test/org/leo/jmg/mem/packer/base64/ExamplePacker.class");

        assertEquals("org.leo.jmg.mem.packer.base64.ExamplePacker",
                PackerScanner.resolveClassName(resource));
        assertEquals(null, PackerScanner.resolveClassName(
                new FileSystemResource("/tmp/test/ExamplePacker.class")));
    }

    @Test
    void discoversPackersWithoutClassLoadingFailures() {
        assertTrue(PackerScanner.scan().getFailures().isEmpty(),
                "扫描不应产生单类加载失败");
    }

    @Test
    void failsFastWhenClasspathCannotBeScanned() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PackerScanner.scan(new FailingResolver(), getClass().getClassLoader()));

        assertTrue(error.getMessage().contains("扫描 Packer 类路径失败"));
        assertTrue(error.getCause() instanceof IOException);
    }

    private static final class FailingResolver implements ResourcePatternResolver {
        @Override
        public Resource[] getResources(String locationPattern) throws IOException {
            throw new IOException("test failure");
        }

        @Override
        public Resource getResource(String location) {
            return new ByteArrayResource(new byte[0]);
        }

        @Override
        public ClassLoader getClassLoader() {
            return PackerScannerTest.class.getClassLoader();
        }
    }
}
