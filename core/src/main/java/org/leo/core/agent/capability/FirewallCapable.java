package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system firewall rules.
 */
public interface FirewallCapable {

    Map<String, Object> getFirewallStatus() throws Exception;

    Map<String, Object> listFirewallRules(String direction, String profile) throws Exception;

    Map<String, Object> addFirewallRule(String ruleName, String direction, String action,
                                        String protocol, String localPort, String remotePort,
                                        String remoteAddress, String rawRule) throws Exception;

    Map<String, Object> deleteFirewallRule(String ruleName, String ruleIndex, String rawRule) throws Exception;

    Map<String, Object> toggleFirewall(boolean enable) throws Exception;
}
