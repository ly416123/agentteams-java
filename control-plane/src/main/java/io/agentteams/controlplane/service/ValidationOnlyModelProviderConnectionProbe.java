package io.agentteams.controlplane.service;

import io.agentteams.controlplane.security.SecretResolver;
import io.agentteams.controlplane.security.ValidationOnlySecretResolver;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Default probe: classify configuration only and never make a network call. */
public final class ValidationOnlyModelProviderConnectionProbe implements ModelProviderConnectionProbe {

    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    private final SecretResolver secretResolver;

    public ValidationOnlyModelProviderConnectionProbe() {
        this(new ValidationOnlySecretResolver());
    }

    public ValidationOnlyModelProviderConnectionProbe(SecretResolver secretResolver) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
    }

    @Override
    public ProbeResult probe(ProbeRequest request) {
        URI uri = parseUri(request.endpoint());
        if (uri == null) {
            return rejected("INVALID_URI", "URI", "INVALID");
        }
        SecretResolver.Resolution credential = secretResolver.resolve(request.credentialReference());
        if (credential.status() == SecretResolver.Status.MISSING) {
            return rejected("CREDENTIAL_REFERENCE_MISSING", "CREDENTIAL_REFERENCE", "MISSING");
        }
        if (credential.status() == SecretResolver.Status.INVALID_REFERENCE) {
            return rejected("CREDENTIAL_REFERENCE_INVALID", "CREDENTIAL_REFERENCE", "INVALID");
        }
        if (credential.status() == SecretResolver.Status.UNAVAILABLE) {
            return rejected("CREDENTIAL_REFERENCE_UNAVAILABLE", "CREDENTIAL_REFERENCE", "UNAVAILABLE");
        }
        if (!validTimeout(request.timeout())) {
            return rejected("TIMEOUT_INVALID", "TIMEOUT", "INVALID");
        }
        return new ProbeResult(ProbeResult.Status.NOT_ATTEMPTED, "VALIDATION_ONLY", false,
                List.of(new ProbeResult.Check("URI", "VALID"),
                        new ProbeResult.Check("CREDENTIAL_REFERENCE", credential.status().name()),
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
