package org.leo.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.init.SeedResourceStore;
import org.leo.core.init.VFSInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillSeedInitializerTest {
    @TempDir Path root;

    @Test
    void synchronizesManagedSkillsWithoutTouchingCustomDirectoriesOrUnchangedFiles() throws Exception {
        var vfs = mock(VFSInitializer.class);
        when(vfs.getRoot()).thenReturn(root);
        var init = new SkillSeedInitializer(vfs, new SeedResourceStore());
        Path custom = root.resolve("skills/platform/custom-fixture/SKILL.md");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "user content");
        init.run();
        Path managed = root.resolve("skills/agent-node/recon-basic-info/SKILL.md");
        String bundled = Files.readString(managed);
        Files.setLastModifiedTime(managed, FileTime.fromMillis(1000));
        init.run();
        assertEquals(1000, Files.getLastModifiedTime(managed).toMillis());
        Files.writeString(managed, "old built-in version");
        init.run();
        assertEquals(bundled, Files.readString(managed));
        assertEquals("user content", Files.readString(custom));
    }

    @Test
    void failedManagedResourcePublicationStopsStartup() throws Exception {
        var vfs = mock(VFSInitializer.class);
        when(vfs.getRoot()).thenReturn(root);
        var store = mock(SeedResourceStore.class);
        when(store.sync(any(), anyString(), any(), eq(true))).thenThrow(new java.io.IOException("fixture"));
        assertThrows(java.io.IOException.class, () -> new SkillSeedInitializer(vfs, store).run());
    }
}
