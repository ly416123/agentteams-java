package io.agentteams.controlplane.security;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Fail-closed policy checks; network calls are deliberately outside this class. */
public final class OutboundPolicyValidator {

    public void validatePolicy(OutboundPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.maxTimeout().compareTo(OutboundPolicy.ABSOLUTE_MAX_TIMEOUT) > 0) {
            throw violation("maxTimeout exceeds the 30 second outbound limit");
        }
    }

    public URI validateEndpoint(String endpoint, OutboundPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        validatePolicy(policy);
        final URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException error) {
            throw violation("endpoint is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !policy.allowedSchemes().contains(scheme)) {
            throw violation("endpoint scheme or host is not allowed");
        }
        if (!allowedDomain(uri.getHost(), policy)) {
            throw violation("endpoint domain is not allowlisted");
        }
        return uri;
    }

    public void validateTimeout(Duration timeout, OutboundPolicy policy) {
        Objects.requireNonNull(timeout, "timeout");
        validatePolicy(policy);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(policy.maxTimeout()) > 0) {
            throw violation("timeout exceeds the outbound policy");
        }
    }

    public void validateTool(String toolName, OutboundPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        validatePolicy(policy);
        if (toolName == null || toolName.isBlank()
                || !(policy.allowedTools().contains("*") || policy.allowedTools().contains(toolName.trim()))) {
            throw violation("tool is not allowlisted");
        }
    }

    private static boolean allowedDomain(String host, OutboundPolicy policy) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return policy.allowedDomains().stream().anyMatch(pattern ->
                "*".equals(pattern) || normalized.equals(pattern.toLowerCase(Locale.ROOT))
                        || (pattern.startsWith("*.")
                                && normalized.endsWith(pattern.substring(1).toLowerCase(Locale.ROOT))));
    }

    private static OutboundPolicyViolationException violation(String message) {
        return new OutboundPolicyViolationException(message);
    }
}
