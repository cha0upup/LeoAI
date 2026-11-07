package org.leo.service.ssh;

import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.session.AgentNodeSession;

/** Outbound port. Implementations use the selected Agent relay and never open a shell during tests. */
public interface SshTransport {
    String probeFingerprint(String user, String clientId, AgentNodeSession parent,
                             ComponentInvokeCapable node, String host, int port) throws Exception;

    void testConnection(String user, String clientId, AgentNodeSession parent,
                         ComponentInvokeCapable node, SshConnectionSpec connection) throws Exception;

    final class AuthenticationException extends Exception {
        public AuthenticationException() { super("SSH 认证失败"); }
    }

    final class HostKeyChangedException extends Exception {
        private final String fingerprint;
        public HostKeyChangedException(String fingerprint) {
            super("SSH 主机指纹校验未通过");
            this.fingerprint = SshInputValidation.requireFingerprint(fingerprint);
        }
        public String fingerprint() { return fingerprint; }
    }
}
