package io.agentteams.runtime;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, non-secret MCP server configuration resolved by the Control Plane. */
public record RuntimeMcpServer(String reference, long revision, String transport, String endpoint,
        String credentialRef, String policyDigest) {
    private static final Pattern CREDENTIAL_REF = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public RuntimeMcpServer {
        reference = requireText(reference, "reference");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        transport = requireText(transport, "transport").toUpperCase(Locale.ROOT);
        if (!transport.equals("SSE") && !transport.equals("STREAMABLE_HTTP")) {
            throw new IllegalArgumentException("unsupported MCP transport: " + transport);
        }
        endpoint = requireEndpoint(endpoint);
        credentialRef = credentialRef == null || credentialRef.isBlank() ? null : credentialRef.trim();
        if (credentialRef != null && !CREDENTIAL_REF.matcher(credentialRef).matches()) {
            throw new IllegalArgumentException("credentialRef must be an environment variable name");
        }
        policyDigest = requireText(policyDigest, "policyDigest");
    }

    private static String requireEndpoint(String value) {
        String endpoint = requireText(value, "endpoint");
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint must be a valid URI", error);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() != null) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URI without credentials or query");
        }
        return endpoint;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
