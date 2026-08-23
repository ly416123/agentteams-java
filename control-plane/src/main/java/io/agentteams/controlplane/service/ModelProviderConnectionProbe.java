package io.agentteams.controlplane.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pluggable provider connection check. The request deliberately contains only
 * a credential reference, never a resolved credential value.
 */
public interface ModelProviderConnectionProbe {

    ProbeResult probe(ProbeRequest request);

    record ProbeRequest(UUID providerId, String providerType, String endpoint,
            String credentialReference, Duration timeout) {
        public ProbeRequest {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(providerType, "providerType");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    record ProbeResult(Status status, String classification, boolean networkCallAttempted,
            List<Check> checks) {
        public ProbeResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            checks = List.copyOf(checks == null ? List.of() : checks);
        }

        public enum Status {
            NOT_ATTEMPTED,
            REJECTED,
            CONNECTED,
            FAILED
        }

        public record Check(String name, String status) {
            public Check {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(status, "status");
            }
        }
    }
}
