package org.leo.ai.tools.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.agent.AgentRuntimeResolver;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.service.SkillManifestService;
import org.leo.core.config.LeoConfig;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillActivationToolsTest {

    @TempDir
    Path tempDir;

    private SkillActivationTools tools;
    private String previousVfsPath;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        tools = new SkillActivationTools(
                new SkillRegistryService(new SkillManifestService()), SkillRegistryService.SCOPE_PUPPET_NODE,
                new AgentRuntimeResolver());
    }

    @AfterEach
    void tearDown() {
        AiToolContext.clear();
        PuppetNodeSessionContainer.clearAllSessions();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void returnsBodyForEnabledSkill() throws Exception {
        writeSkill("enabled-skill", true, "# Enabled\n\nInstructions.");

        String activated = tools.activateSkill("enabled-skill");
        assertEquals(true, activated.contains("<skill_execution_policy>"));
        assertEquals(true, activated.contains("risk: low"));
        assertEquals(true, activated.endsWith("# Enabled\n\nInstructions.\n"));
    }

    @Test
    void refusesDisabledAndTraversalNames() throws Exception {
        writeSkill("disabled-skill", false, "disabled");

        assertThrows(AiToolException.class, () -> tools.activateSkill("disabled-skill"));
        assertThrows(AiToolException.class,
                () -> tools.activateSkill("../platform/exploit-suggest"));
    }

    @Test
    void recordsActivationOnCurrentAgentThread() throws Exception {
        writeSkill("enabled-skill", true, "# Enabled");
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("activation-session");
        AiThread thread = session.createAiThread("thread-1", "activation");
        PuppetNodeSessionContainer.addSession("activation-session", session);
        AiToolContext.setFromMemoryId("activation-session:thread-1");
        SkillActivationTools statefulTools = new SkillActivationTools(
                new SkillRegistryService(new SkillManifestService()), SkillRegistryService.SCOPE_PUPPET_NODE,
                new AgentRuntimeResolver());

        statefulTools.activateSkill("enabled-skill");

        assertTrue(thread.getActivatedSkills().contains("enabled-skill"));
    }

    private void writeSkill(String name, boolean enabled, String body) throws Exception {
        Path skillDir = tempDir.resolve("skills").resolve("puppet-node").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: test skill
                ---

                %s
                """.formatted(name, body));
        Files.writeString(skillDir.resolve("manifest.yaml"), """
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
                pack: test
                risk: low
                accessMode: read-only
                status: published
                source: custom
                owner: test
                enabled: %s
                """.formatted(name, name, enabled));
    }
}
