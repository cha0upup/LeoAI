package org.leo.web.dto.agentnode.command;

import org.leo.web.exception.ApiException;

/** Server-resolved connection parameters; credentials never enter logs or public responses. */
public record SshConnectRequest(String processId, String host, Integer port,
                                String username, String authType, String password, String privateKey,
                                String passphrase, String fingerprint) {
    public SshConnectRequest normalized() {
        String pid = id(processId);
        String address = normalizeHost(host);
        int p = normalizePort(port);
        String user = normalizeUsername(username);
        String auth = normalizeAuthType(authType);
        boolean keyAuth = "privateKey".equals(auth);
        validateCredentials(auth, password, privateKey, passphrase);
        if (fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw ApiException.badRequest("请先核验 SSH 主机指纹");
        return new SshConnectRequest(pid, address, p, user, auth, keyAuth ? null : password,
                keyAuth ? privateKey : null, keyAuth ? passphrase : null,
                fingerprint);
    }
    public static String normalizeHost(String value) {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty() || address.length() > 255 || address.chars().anyMatch(c -> Character.isWhitespace(c)
                || Character.isISOControl(c) || c == '/' || c == '\\')) throw ApiException.badRequest("SSH 主机地址无效");
        return address;
    }
    public static int normalizePort(Integer value) {
        int port = value == null ? 22 : value;
        if (port < 1 || port > 65535) throw ApiException.badRequest("SSH 端口应在 1–65535 之间");
        return port;
    }
    public static String normalizeUsername(String value) {
        String user = value == null ? "" : value.trim();
        if (user.isEmpty() || user.length() > 128 || user.chars().anyMatch(Character::isISOControl))
            throw ApiException.badRequest("SSH 用户名无效");
        return user;
    }
    public static String normalizeAuthType(String value) {
        String auth = value == null ? "password" : value;
        if (!"password".equals(auth) && !"privateKey".equals(auth)) throw ApiException.badRequest("SSH 认证方式无效");
        return auth;
    }
    public static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.length() > 120) throw ApiException.badRequest("SSH 连接名称超过长度限制");
        return name;
    }
    public static void validateCredentialSizes(String password, String privateKey, String passphrase) {
        if (password != null && password.length() > 8192 || passphrase != null && passphrase.length() > 8192
                || privateKey != null && privateKey.length() > 131072) throw ApiException.badRequest("SSH 凭据超过长度限制");
    }
    public static void validateCredentials(String auth, String password, String privateKey, String passphrase) {
        validateCredentialSizes(password, privateKey, passphrase);
        if ("password".equals(auth) && (password == null || password.isEmpty())) throw ApiException.badRequest("请输入 SSH 密码");
        if ("privateKey".equals(auth) && (privateKey == null || privateKey.isBlank())) throw ApiException.badRequest("请输入 SSH 私钥");
    }
    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw ApiException.badRequest("会话标识无效");
        return value;
    }
    @Override public String toString() { return "SshConnectRequest[credentials redacted]"; }
}
