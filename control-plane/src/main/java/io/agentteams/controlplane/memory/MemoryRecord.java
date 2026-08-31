package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable memory metadata; content stays behind a controlled reference. */
public record MemoryRecord(UUID id, MemoryPolicy policy, String contentRef, String summary, String source,
        Instant expiresAt, Instant createdAt, Instant updatedAt, long version, GovernanceStatus governanceStatus) {
    public MemoryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        required(contentRef, "contentRef");
        summary = summary == null ? "" : summary.trim();
        required(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(governanceStatus, "governanceStatus");
    }

    public MemoryRecord(UUID id, MemoryPolicy policy, String contentRef, String summary, String source,
            Instant expiresAt, Instant createdAt, Instant updatedAt, long version) {
        this(id, policy, contentRef, summary, source, expiresAt, createdAt, updatedAt, version,
                GovernanceStatus.ACTIVE);
    }

    public MemoryRecord withPolicy(MemoryPolicy nextPolicy, Instant at) {
        return new MemoryRecord(id, nextPolicy, contentRef, summary, source, expiresAt, createdAt, at,
                version + 1, governanceStatus);
    }

    public MemoryRecord withGovernanceStatus(GovernanceStatus nextStatus, Instant at) {
        return new MemoryRecord(id, policy, contentRef, summary, source, expiresAt, createdAt, at,
                version + 1, nextStatus);
    }

    public enum GovernanceStatus { ACTIVE, FROZEN, DELETED }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
