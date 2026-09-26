package org.leo.web.controller.platform.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillExportService;
import org.leo.ai.service.SkillFileService;
import org.leo.ai.service.SkillManifestService;
import org.leo.ai.service.SkillOperationLock;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.config.LeoConfig;
import org.leo.web.service.SkillManagementService;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.spy;

abstract class SkillControllerTestSupport {
    @TempDir Path tempDir;
    private String previousVfsPath;
    SkillManifestService manifestService;
    SkillRegistryService registry;
    LeoSkillsProvider provider;
    SkillFileService fileService;
    SkillOperationLock operationLock;
    SkillExportService archives;
    SkillManagementService management;
    SkillController controller;

    @BeforeEach
    void setUpSkills() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        manifestService = new SkillManifestService();
        registry = spy(new SkillRegistryService(manifestService));
        provider = new LeoSkillsProvider(registry);
        fileService = spy(new SkillFileService());
        configure(spy(new SkillOperationLock()));
    }

    void configure(SkillOperationLock locks) {
        operationLock = locks;
        archives = spy(new SkillExportService(manifestService, operationLock));
        management = new SkillManagementService(registry, manifestService, fileService,
                archives, operationLock);
        controller = new SkillController(registry, fileService, management);
    }

    @AfterEach
    void restoreConfig() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    Path skillDir(String name) {
        return tempDir.resolve("skills/puppet-node").resolve(name);
    }

    void writeSkill(String name, String body) throws IOException {
        writeSkill(name, body, "published", true, false);
    }

    void writeSkill(String name, String status, boolean enabled, boolean unknownField) throws IOException {
        writeSkill(name, "body", status, enabled, unknownField);
    }

    private void writeSkill(String name, String body, String status, boolean enabled, boolean unknownField)
            throws IOException {
        Files.createDirectories(skillDir(name));
        Files.writeString(skillDir(name).resolve("SKILL.md"), skill(name, body));
        Files.writeString(skillDir(name).resolve("manifest.yaml"), manifest(name, status, enabled)
                + (unknownField ? "unknownField: true\n" : ""));
    }

    static String skill(String name, String body) {
        return "---\nname: " + name + "\ndescription: test skill\n---\n\n" + body + "\n";
    }

    static String manifest(String name) {
        return manifest(name, "published", true);
    }

    private static String manifest(String name, String status, boolean enabled) {
        return """
                schemaVersion: 1
                id: leo.test.%s
                name: %s
                version: 1.0.0
                scope: puppet-node
                domain: operation
                category: discovery
                mode: assess
                platforms: [linux]
                targets: [host]
                risk: low
                accessMode: read-only
                status: %s
                source: custom
                owner: test
                enabled: %s
                """.formatted(name, name, status, enabled);
    }
}
