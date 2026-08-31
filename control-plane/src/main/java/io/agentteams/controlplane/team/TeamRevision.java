package io.agentteams.controlplane.team;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable Team configuration revision. Published revisions are never updated in place. */
public record TeamRevision(UUID teamId, long revision, UUID leaderAgentId, String overlayJson,
        String digest, TeamRevisionStatus status, Long rollbackOfRevision, String createdBy,
        Instant createdAt, long version, List<UUID> memberAgentIds,
        List<TeamResourceBinding> resourceBindings) {
    public TeamRevision {
        Objects.requireNonNull(teamId, "teamId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(leaderAgentId, "leaderAgentId");
        requireText(overlayJson, "overlayJson");
        requireText(digest, "digest");
        Objects.requireNonNull(status, "status");
        requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        memberAgentIds = List.copyOf(Objects.requireNonNull(memberAgentIds, "memberAgentIds"));
        if (memberAgentIds.stream().distinct().count() != memberAgentIds.size()) {
            throw new IllegalArgumentException("revision members must be unique");
        }
        if (!memberAgentIds.contains(leaderAgentId)) {
            throw new IllegalArgumentException("leader must be a revision member");
        }
        resourceBindings = List.copyOf(TeamResourceBindings.canonicalize(
                Objects.requireNonNull(resourceBindings, "resourceBindings")));
    }

    public TeamRevision(UUID teamId, long revision, UUID leaderAgentId, String overlayJson,
            String digest, TeamRevisionStatus status, Long rollbackOfRevision, String createdBy,
            Instant createdAt, long version, List<UUID> memberAgentIds) {
        this(teamId, revision, leaderAgentId, overlayJson, digest, status, rollbackOfRevision,
                createdBy, createdAt, version, memberAgentIds, List.of());
    }

    public TeamRevision(UUID teamId, long revision, UUID leaderAgentId, String overlayJson,
            String digest, TeamRevisionStatus status, Long rollbackOfRevision, String createdBy,
            Instant createdAt, long version) {
        this(teamId, revision, leaderAgentId, overlayJson, digest, status, rollbackOfRevision,
                createdBy, createdAt, version, List.of(leaderAgentId), List.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
