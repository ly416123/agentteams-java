package io.agentteams.controlplane.agentspec;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Non-secret MCP connection metadata embedded in a worker configuration binding. */
public record McpRuntimeMetadata(String serverId, String transport, String endpoint, String credentialRef) {
    private static final Pattern CREDENTIAL_REF = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public McpRuntimeMetadata {
        serverId = requireText(serverId, "serverId");
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("serverId contains unsupported characters");
        }
        transport = requireText(transport, "transport").toUpperCase(Locale.ROOT);
        if (!transport.equals("SSE") && !transport.equals("STREAMABLE_HTTP")) {
            throw new IllegalArgumentException("unsupported MCP transport: " + transport);
        }
        endpoint = requireEndpoint(endpoint);
        credentialRef = credentialRef == null || credentialRef.isBlank() ? null : credentialRef.trim();
        if (credentialRef != null && !CREDENTIAL_REF.matcher(credentialRef).matches()) {
            throw new IllegalArgumentException("credentialRef must be an environment variable name");
        }
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
