package io.agentteams.controlplane.config;

import io.agentteams.controlplane.team.TeamResourceBinding;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EffectiveConfigRequest(UUID agentBaseSnapshotId, UUID agentId, UUID teamId, long teamRevision, UUID taskId,
        List<String> bindingDigests, List<TeamResourceBinding> resourceBindings,
        String baseManifest, String teamOverlay, String taskOverlay) {
    public EffectiveConfigRequest {
        Objects.requireNonNull(agentBaseSnapshotId, "agentBaseSnapshotId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(taskId, "taskId");
        if (teamRevision < 1) throw new IllegalArgumentException("teamRevision must be positive");
        bindingDigests = List.copyOf(Objects.requireNonNull(bindingDigests, "bindingDigests"));
        if (bindingDigests.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("bindingDigests must not contain blank values");
        }
        resourceBindings = List.copyOf(Objects.requireNonNull(resourceBindings, "resourceBindings"));
        requireJsonText(baseManifest, "baseManifest");
        requireJsonText(teamOverlay, "teamOverlay");
        requireJsonText(taskOverlay, "taskOverlay");
    }

    public EffectiveConfigRequest(UUID agentBaseSnapshotId, UUID agentId, UUID teamId, long teamRevision, UUID taskId,
            List<String> bindingDigests, String baseManifest, String teamOverlay, String taskOverlay) {
        this(agentBaseSnapshotId, agentId, teamId, teamRevision, taskId, bindingDigests, List.of(), baseManifest,
                teamOverlay, taskOverlay);
    }

    public EffectiveConfigRequest(UUID agentId, UUID teamId, long teamRevision, UUID taskId,
            String baseManifest, String teamOverlay, String taskOverlay) {
        this(snapshotId(baseManifest), agentId, teamId, teamRevision, taskId, List.of(), List.of(), baseManifest,
                teamOverlay, taskOverlay);
    }

    private static UUID snapshotId(String manifest) {
        return UUID.nameUUIDFromBytes((manifest == null ? "" : manifest)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void requireJsonText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
