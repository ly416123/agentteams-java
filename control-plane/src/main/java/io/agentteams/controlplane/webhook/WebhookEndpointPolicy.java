package io.agentteams.controlplane.webhook;

import java.net.InetAddress;
import java.net.URI;

/** SSRF guard shared by subscription validation and every outbound delivery attempt. */
final class WebhookEndpointPolicy {
    private WebhookEndpointPolicy() { }

    static URI requireSafe(String value) {
        try {
            URI endpoint = URI.create(required(value, "endpoint"));
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException("endpoint must be HTTPS without user info");
            }
            for (InetAddress address : InetAddress.getAllByName(endpoint.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("endpoint resolves to a private address");
                }
            }
            return endpoint;
        } catch (java.net.UnknownHostException error) {
            throw new IllegalArgumentException("endpoint host cannot be resolved", error);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("endpoint")) throw error;
            throw new IllegalArgumentException("endpoint is invalid", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
