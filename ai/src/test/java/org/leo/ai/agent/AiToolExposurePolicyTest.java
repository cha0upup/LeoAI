package org.leo.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.SkillMeta;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiToolExposurePolicyTest {

    private static final String PLATFORM_STATE_ID = "tool-exposure-platform";

    @AfterEach
    void cleanUp() {
        PlatformAiStateStore.remove(PLATFORM_STATE_ID);
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void exposesSkillToolsOnlyAfterActivationIncludingDependencies() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SkillMeta parent = skill("parent-skill", List.of("parentAction"),
                List.of("dependency-skill"));
        SkillMeta dependency = skill("dependency-skill", List.of("dependencyAction"),
                List.of());
        when(registry.listSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(parent, dependency));
        when(registry.listAllSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(parent, dependency));
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AiRuntimeResolver(), registry);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);

        List<String> candidates = List.of("parentAction", "dependencyAction");
        assertTrue(policy.visibleToolNames(AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                PLATFORM_STATE_ID, candidates).isEmpty());

        state.activateSkill("parent-skill");

        assertEquals(Set.copyOf(candidates), policy.visibleToolNames(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, PLATFORM_STATE_ID, candidates));
    }

    @Test
    void keepsCoreToolsVisibleEvenWhenAManifestMentionsThem() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SkillMeta workspaceSkill = skill("workspace-skill",
                List.of("workspaceReadText"), List.of());
        when(registry.listSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(workspaceSkill));
        when(registry.listAllSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(workspaceSkill));
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AiRuntimeResolver(), registry);

        assertEquals(Set.of("workspaceReadText"), policy.visibleToolNames(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, PLATFORM_STATE_ID, List.of("workspaceReadText")));
    }

    @Test
    void filtersAgentToolsByRuntimeCapability() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        when(registry.listSkills(SkillRegistryService.SCOPE_AGENT_NODE))
                .thenReturn(List.of());
        when(registry.listAllSkills(SkillRegistryService.SCOPE_AGENT_NODE))
                .thenReturn(List.of());
        AbstractAgentNode node = mock(AbstractAgentNode.class,
                withSettings().extraInterfaces(BasicInfoCapable.class));
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("capability-session");
        session.setAgentNode(node);
        AgentNodeSessionContainer.addSession("capability-session", session);
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AiRuntimeResolver(), registry);

        assertEquals(Set.of("getBasicInfo"), policy.visibleToolNames(
                AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE,
                "capability-session", List.of("getBasicInfo", "getClassBytecode")));
    }

    @Test
    void keepsToolsFromDisabledPublishedSkillsHidden() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SkillMeta disabled = skill("disabled-skill", List.of("disabledAction"), List.of());
        when(registry.listSkills(SkillRegistryService.SCOPE_PLATFORM)).thenReturn(List.of());
        when(registry.listAllSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(disabled));
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AiRuntimeResolver(), registry);

        assertTrue(policy.visibleToolNames(AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                PLATFORM_STATE_ID, List.of("disabledAction")).isEmpty());
    }

    @Test
    void ignoresBlankToolNamesAndReadsEachSkillCatalogOnlyOncePerBatch() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        AiToolExposurePolicy policy = new AiToolExposurePolicy(new AiRuntimeResolver(), registry);

        assertEquals(Set.of("workspaceReadText", "getDisguises"), policy.visibleToolNames(
                AiToolAuthorizationPolicy.AiAgentScope.PLATFORM, PLATFORM_STATE_ID,
                Arrays.asList(null, "", " ", "workspaceReadText", "getDisguises", "workspaceReadText")));

        verify(registry).listSkills(SkillRegistryService.SCOPE_PLATFORM);
        verify(registry).listAllSkills(SkillRegistryService.SCOPE_PLATFORM);
    }

    @Test
    void emptyBatchesDoNotResolveRuntimeOrReadTheSkillCatalog() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        AiRuntimeResolver resolver = mock(AiRuntimeResolver.class);
        AiToolExposurePolicy policy = new AiToolExposurePolicy(resolver, registry);

        assertTrue(policy.visibleToolNames(AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                PLATFORM_STATE_ID, null).isEmpty());
        assertTrue(policy.visibleToolNames(AiToolAuthorizationPolicy.AiAgentScope.PLATFORM,
                PLATFORM_STATE_ID, List.of()).isEmpty());
        verifyNoInteractions(registry, resolver);
    }

    private static SkillMeta skill(String name, List<String> requiredTools,
                                   List<String> requiredSkills) {
        SkillMeta skill = mock(SkillMeta.class);
        when(skill.getName()).thenReturn(name);
        when(skill.getRequiredTools()).thenReturn(requiredTools);
        when(skill.getRequiredSkills()).thenReturn(requiredSkills);
        when(skill.isValid()).thenReturn(true);
        when(skill.getStatus()).thenReturn("published");
        return skill;
    }
}
