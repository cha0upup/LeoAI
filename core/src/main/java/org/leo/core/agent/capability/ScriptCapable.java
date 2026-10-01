package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can execute lightweight scripts.
 */
public interface ScriptCapable {

    Map<String, Object> execScript(String language, String script) throws Exception;
}
