package org.leo.core.runtime;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;

/** Equal-status runtime module SPI implemented by javacore and phpcore. */
public interface AgentRuntimeModule {

    AgentRuntime getRuntime();

    default boolean isReady() {
        return true;
    }

    AbstractAgentNode createNode(Agent agent,
                                  User user,
                                  AgentNodeCreationContext context) throws Exception;
}
