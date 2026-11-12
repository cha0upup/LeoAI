package org.leo.web.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.ai.service.skill.SkillRegistryService;
import org.leo.core.entity.Agent;
import org.leo.web.controller.agentnode.ai.NodeAiController;
import org.leo.web.controller.platform.agent.AgentManageController;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Locks the execution-node versus AI-agent naming boundary. */
class AgentNamingContractTest {

    @Test
    void serializesExecutionNodeIdentifiersUsingAgentNames() throws Exception {
        Agent node = new Agent();
        node.setAgentId("node-1");
        node.setAgentName("Java node");
        node.setParentAgentId("root");
        var json = new ObjectMapper().valueToTree(node);
        assertEquals("node-1", json.get("agentId").asText());
        assertEquals("Java node", json.get("agentName").asText());
        assertEquals("root", json.get("parentAgentId").asText());
        assertFalse(json.has("puppetId"));
        assertFalse(json.has("puppetName"));
        assertFalse(json.has("parentPuppetId"));
    }

    @Test
    void exposesOnlyTheNewNodeRouteAndSkillScope() {
        assertEquals("/platform/agent-manage", AgentManageController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("/agent-node/ai", NodeAiController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("agent-node", SkillRegistryService.SCOPE_AGENT_NODE);
    }
}
