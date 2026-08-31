package io.agentteams.controlplane.config;

import io.agentteams.controlplane.team.TeamResourceBinding;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Non-secret provenance for an immutable effective configuration snapshot. */
public record ConfigProvenance(UUID agentBaseSnapshotId, UUID agentId, UUID teamId, long teamRevision, UUID taskId,
        List<String> bindingDigests, List<TeamResourceBinding> resourceBindings, String schemaVersion) {
    public ConfigProvenance {
        Objects.requireNonNull(agentBaseSnapshotId, "agentBaseSnapshotId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(taskId, "taskId");
        if (teamRevision < 1) throw new IllegalArgumentException("teamRevision must be positive");
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        bindingDigests = List.copyOf(Objects.requireNonNull(bindingDigests, "bindingDigests"));
        if (bindingDigests.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("bindingDigests must not contain blank values");
        }
        resourceBindings = resourceBindings == null ? List.of() : List.copyOf(resourceBindings);
    }

    public ConfigProvenance(UUID agentBaseSnapshotId, UUID agentId, UUID teamId, long teamRevision, UUID taskId,
            List<String> bindingDigests, String schemaVersion) {
        this(agentBaseSnapshotId, agentId, teamId, teamRevision, taskId, bindingDigests, List.of(), schemaVersion);
    }

    public ConfigProvenance(UUID agentId, UUID teamId, long teamRevision, UUID taskId) {
        this(UUID.nameUUIDFromBytes((agentId + ":" + teamId + ":" + teamRevision)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                agentId, teamId, teamRevision, taskId, List.of(), List.of(), "v1");
    }
}
