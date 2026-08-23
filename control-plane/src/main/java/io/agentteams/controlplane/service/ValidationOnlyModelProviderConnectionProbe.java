package io.agentteams.controlplane.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Default probe: classify configuration only and never make a network call. */
public final class ValidationOnlyModelProviderConnectionProbe implements ModelProviderConnectionProbe {

    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    @Override
    public ProbeResult probe(ProbeRequest request) {
        URI uri = parseUri(request.endpoint());
        if (uri == null) {
            return rejected("INVALID_URI", "URI", "INVALID");
        }
        if (request.credentialReference() == null || request.credentialReference().isBlank()) {
            return rejected("CREDENTIAL_REFERENCE_MISSING", "CREDENTIAL_REFERENCE", "MISSING");
        }
        if (!validTimeout(request.timeout())) {
            return rejected("TIMEOUT_INVALID", "TIMEOUT", "INVALID");
        }
        return new ProbeResult(ProbeResult.Status.NOT_ATTEMPTED, "VALIDATION_ONLY", false,
                List.of(new ProbeResult.Check("URI", "VALID"),
                        new ProbeResult.Check("CREDENTIAL_REFERENCE", "CONFIGURED"),
                        new ProbeResult.Check("TIMEOUT", "ACCEPTED")));
    }

    private static URI parseUri(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean validTimeout(Duration timeout) {
        return !timeout.isZero() && !timeout.isNegative()
                && timeout.toMillis() <= MAX_TIMEOUT_MILLIS;
    }

    private static ProbeResult rejected(String classification, String checkName, String checkStatus) {
        return new ProbeResult(ProbeResult.Status.REJECTED, classification, false,
                List.of(new ProbeResult.Check(checkName, checkStatus)));
    }
}
