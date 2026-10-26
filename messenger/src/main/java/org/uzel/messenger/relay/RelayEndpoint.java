package org.uzel.messenger.relay;

import java.util.Objects;

/** A relay the invitation advertises: host:port:pin, where pin is the relay certificate. */
public record RelayEndpoint(String host, int port, String pin) {
    public RelayEndpoint {
        Objects.requireNonNull(host);
        Objects.requireNonNull(pin);
        if (!host.matches("[A-Za-z0-9_.:%-]{1,253}")) throw new IllegalArgumentException("Invalid relay host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid relay port");
        if (!pin.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid relay certificate pin");
    }

    public String encode() {
        return host + ":" + port + ":" + pin;
    }

    public static RelayEndpoint parse(String value) {
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("Relay must be host:port:pin");
        return new RelayEndpoint(parts[0], Integer.parseInt(parts[1]), parts[2].toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isValid(String value) {
        try {
            return value == null || value.isEmpty() || parse(value) != null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
