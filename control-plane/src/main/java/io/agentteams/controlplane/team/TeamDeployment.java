package io.agentteams.controlplane.team;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable aggregate for one explicit Team revision deployment. */
public record TeamDeployment(UUID id, UUID teamId, long teamRevision, String status,
        List<Member> members, Instant createdAt, String idempotencyKey) {
    public TeamDeployment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        if (teamRevision < 1) throw new IllegalArgumentException("teamRevision must be positive");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(createdAt, "createdAt");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    public static TeamDeployment create(UUID id, UUID teamId, long teamRevision,
            List<Member> members, Instant createdAt) {
        return new TeamDeployment(id, teamId, teamRevision, "PENDING", members, createdAt);
    }

    public TeamDeployment(UUID id, UUID teamId, long teamRevision, String status,
            List<Member> members, Instant createdAt) {
        this(id, teamId, teamRevision, status, members, createdAt, "legacy-" + id);
    }

    public static TeamDeployment create(UUID id, UUID teamId, long teamRevision,
            List<Member> members, Instant createdAt, String idempotencyKey) {
        return new TeamDeployment(id, teamId, teamRevision, "PENDING", members, createdAt, idempotencyKey);
    }

    public record Member(UUID agentId, String baseManifest, String taskOverlay,
            UUID bindingId, String status, String failureCode) {
        public Member {
            Objects.requireNonNull(agentId, "agentId");
            if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        }

        public Member(UUID agentId) {
            this(agentId, null, "{}", null, "PENDING", null);
        }

        public Member(UUID agentId, String baseManifest, String taskOverlay) {
            this(agentId, baseManifest, taskOverlay, null, "PENDING", null);
        }
    }
}
