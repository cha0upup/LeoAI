package org.leo.service.discovery;

/** Missing and out-of-scope tasks deliberately share the same application failure. */
public final class NetworkProbeTaskNotFoundException extends RuntimeException {
    public NetworkProbeTaskNotFoundException(String message) {
        super(message);
    }
}
