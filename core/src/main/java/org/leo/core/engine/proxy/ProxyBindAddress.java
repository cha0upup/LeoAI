package org.leo.core.engine.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Numeric local bind addresses, without DNS lookups for malformed user input. */
public final class ProxyBindAddress {
    public static final String DEFAULT = "0.0.0.0";

    private ProxyBindAddress() { }

    public static String normalize(String value) {
        if (value == null) return DEFAULT;
        if (value.chars().anyMatch(Character::isISOControl)) throw invalidAddress();
        if (value.isBlank()) return DEFAULT;
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
            if (!address.contains(":")) throw invalidAddress();
        }
        if (address.contains(":")) {
            // Restrict input to a numeric IPv6 literal before asking the JDK to parse it.
            // A scope may identify a local interface (for example, fe80::1%en0).
            if (!address.matches("[0-9a-fA-F:.]+(?:%[0-9a-zA-Z_.-]+)?")) throw invalidAddress();
        } else {
            if (!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw invalidAddress();
            for (String part : address.split("\\.")) {
                if (Integer.parseInt(part) > 255) throw invalidAddress();
            }
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException | IllegalArgumentException error) {
            throw invalidAddress();
        }
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("bindAddr必须是有效的IPv4或IPv6地址");
    }
}
