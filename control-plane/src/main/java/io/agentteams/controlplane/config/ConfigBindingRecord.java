package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConfigBindingRecord(UUID id, String subject, UUID agentId, UUID snapshotId, Instant desiredAt) {
    public ConfigBindingRecord {
        Objects.requireNonNull(id, "id");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(desiredAt, "desiredAt");
    }
}
