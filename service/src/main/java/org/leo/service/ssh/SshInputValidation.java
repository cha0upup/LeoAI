package org.leo.service.ssh;

import org.leo.service.ssh.SshConnectionException.Kind;

import java.util.UUID;
import java.util.regex.Pattern;

/** Shared SSH field rules for public requests and server-resolved connections. */
public final class SshInputValidation {
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    private SshInputValidation() { }

    public static String requireSessionId(String value) {
        if (value == null || !SESSION_ID.matcher(value).matches()) {
            throw new SshConnectionException(Kind.VALIDATION, "会话标识无效");
        }
        return value;
    }

    public static String normalizeProfileId(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException error) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 配置标识无效");
        }
    }

    public static long requireVersion(Long value) {
        if (value == null || value < 1 || value > Integer.MAX_VALUE) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 配置版本无效");
        }
        return value;
    }

    public static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty() || host.length() > 255 || host.chars().anyMatch(c -> Character.isWhitespace(c)
                || Character.isISOControl(c) || c == '/' || c == '\\')) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 主机地址无效");
        }
        return host;
    }

    public static int normalizePort(Integer value) {
        int port = value == null ? 22 : value;
        if (port < 1 || port > 65535) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 端口应在 1–65535 之间");
        }
        return port;
    }

    public static String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (username.isEmpty() || username.length() > 128 || username.chars().anyMatch(Character::isISOControl)) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 用户名无效");
        }
        return username;
    }

    public static String normalizeAuthType(String value) {
        String authType = value == null ? "password" : value;
        if (!"password".equals(authType) && !"privateKey".equals(authType)) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 认证方式无效");
        }
        return authType;
    }

    public static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.length() > 120) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 连接名称超过长度限制");
        }
        return name;
    }

    public static void validateCredentialSizes(String password, String privateKey, String passphrase) {
        if ((password != null && password.length() > 8192)
                || (passphrase != null && passphrase.length() > 8192)
                || (privateKey != null && privateKey.length() > 131072)) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 凭据超过长度限制");
        }
    }

    public static void validateCredentials(String authType, String password, String privateKey, String passphrase) {
        validateCredentialSizes(password, privateKey, passphrase);
        if ("password".equals(authType) && (password == null || password.isEmpty())) {
            throw new SshConnectionException(Kind.VALIDATION, "请输入 SSH 密码");
        }
        if ("privateKey".equals(authType) && (privateKey == null || privateKey.isBlank())) {
            throw new SshConnectionException(Kind.VALIDATION, "请输入 SSH 私钥");
        }
    }

    /** A saved pin may supply an omitted browser fingerprint. */
    public static String optionalFingerprint(String value) {
        if (value != null && !validFingerprint(value)) {
            throw new SshConnectionException(Kind.VALIDATION, "SSH 主机指纹格式无效");
        }
        return value;
    }

    /** The transport must always receive an explicitly confirmed or previously saved pin. */
    public static String requireFingerprint(String value) {
        if (!validFingerprint(value)) {
            throw new SshConnectionException(Kind.VALIDATION, "请先核验 SSH 主机指纹");
        }
        return value;
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }
}
