package io.agentteams.controlplane.team;

import io.agentteams.controlplane.config.EffectiveConfig;
import io.agentteams.controlplane.config.EffectiveConfigComposer;
import io.agentteams.controlplane.config.EffectiveConfigRequest;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Uses the existing Config Snapshot/Deployment pipeline for Team member rollout. */
public final class TeamDeploymentService {
    private final TeamDeploymentRepository repository;
    private final ConfigSnapshotService snapshots;
    private final ConfigDeploymentService deployments;
    private final EffectiveConfigComposer composer;
    private final Clock clock;
    private final TeamRevisionRepository revisions;
    private final ResourceScopeRepository resourceScopes;

    public TeamDeploymentService(TeamDeploymentRepository repository, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, Clock clock) {
        this(repository, snapshots, deployments, new EffectiveConfigComposer(), clock, null);
    }

    public TeamDeploymentService(TeamDeploymentRepository repository, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, Instant now) {
        this(repository, snapshots, deployments, new EffectiveConfigComposer(), Clock.fixed(now, java.time.ZoneOffset.UTC), null);
    }

    public TeamDeploymentService(TeamDeploymentRepository repository, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, EffectiveConfigComposer composer, Clock clock) {
        this(repository, snapshots, deployments, composer, clock, null);
    }

    public TeamDeploymentService(TeamDeploymentRepository repository, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, EffectiveConfigComposer composer, Clock clock,
            TeamRevisionRepository revisions) {
        this(repository, snapshots, deployments, composer, clock, revisions, null);
    }

    public TeamDeploymentService(TeamDeploymentRepository repository, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, EffectiveConfigComposer composer, Clock clock,
            TeamRevisionRepository revisions, ResourceScopeRepository resourceScopes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.revisions = revisions;
        this.resourceScopes = resourceScopes;
    }

    public TeamDeployment deploy(TeamRevision revision, List<TeamDeployment.Member> members,
            String actor, String idempotencyKey) {
        Objects.requireNonNull(revision, "revision");
        requireTeamScope(revision.teamId());
        if (revision.status() != TeamRevisionStatus.PUBLISHED) {
            throw new TeamRevisionConflictException("deployment requires a PUBLISHED revision");
        }
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("members must not be empty");
        java.util.Set<UUID> expectedMembers = new java.util.LinkedHashSet<>(revision.memberAgentIds());
        java.util.Set<UUID> requestedMembers = members.stream().map(TeamDeployment.Member::agentId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (requestedMembers.size() != members.size() || !requestedMembers.equals(expectedMembers)) {
            throw new TeamRevisionConflictException("deployment member set must equal the published revision");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        java.util.Optional<TeamDeployment> existing = repository.findByIdempotencyKey(revision.teamId(), idempotencyKey);
        if (existing.isPresent()) return existing.get();
        TeamDeployment deployment = repository.create(TeamDeployment.create(UUID.randomUUID(), revision.teamId(),
                revision.revision(), members, clock.instant(), idempotencyKey));
        for (TeamDeployment.Member member : members) {
            try {
                applyMember(deployment, revision, member, actor);
            } catch (RuntimeException failure) {
                repository.markMember(deployment.id(), member.agentId(), member.bindingId(), "FAILED",
                        failure.getClass().getSimpleName());
            }
        }
        repository.refreshStatus(deployment.id());
        return repository.find(deployment.id()).orElse(deployment);
    }

    public void retry(UUID deploymentId) {
        retryInternal(deploymentId, "legacy-retry-" + deploymentId + "-" + clock.instant().toEpochMilli(), false);
    }

    public void retry(UUID deploymentId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        retryInternal(deploymentId, idempotencyKey, true);
    }

    private void retryInternal(UUID deploymentId, String idempotencyKey, boolean persistKey) {
        TeamDeployment deployment = repository.find(Objects.requireNonNull(deploymentId, "deploymentId"))
                .orElseThrow(() -> new IllegalArgumentException("team deployment does not exist"));
        requireTeamScope(deployment.teamId());
        List<TeamDeployment.Member> failed = repository.failedMembers(deployment.id());
        if (failed.isEmpty()) throw new IllegalArgumentException("team deployment has no failed members");
        if (persistKey) {
            String hash = sha256(deployment.id() + "\u0000" + deployment.version() + "\u0000"
                    + failed.stream().map(TeamDeployment.Member::agentId).sorted().toList());
            if (!repository.claimRetry(deployment.id(), failed.stream().map(TeamDeployment.Member::agentId).toList(),
                    deployment.version(), idempotencyKey, hash)) return;
        } else {
            repository.markRetrying(deployment.id(), failed.stream().map(TeamDeployment.Member::agentId).toList(),
                    clock.instant());
        }
        for (TeamDeployment.Member member : failed) {
            try {
                if (member.bindingId() != null) {
                    deployments.retry(member.bindingId());
                } else if (member.baseManifest() != null && !member.baseManifest().isBlank()) {
                    if (revisions != null) {
                        TeamRevision revision = revisions.find(deployment.teamId(), deployment.teamRevision())
                                .orElseThrow(() -> new IllegalStateException("team revision does not exist"));
                        applyMember(deployment, revision, member, "team-deployment-retry");
                    } else {
                        applyMemberWithoutRevision(deployment, member, "team-deployment-retry");
                    }
                } else {
                    throw new IllegalStateException("failed member has no config binding or base manifest");
                }
            } catch (RuntimeException failure) {
                repository.markMember(deployment.id(), member.agentId(), member.bindingId(), "FAILED",
                        failure.getClass().getSimpleName());
            }
        }
        repository.refreshStatus(deployment.id());
    }

    public TeamDeployment find(UUID deploymentId, UUID teamId) {
        requireTeamScope(teamId);
        TeamDeployment deployment = repository.find(Objects.requireNonNull(deploymentId, "deploymentId"))
                .orElseThrow(() -> new IllegalArgumentException("team deployment does not exist"));
        if (!deployment.teamId().equals(Objects.requireNonNull(teamId, "teamId"))) {
            throw new IllegalArgumentException("team deployment is outside the requested team");
        }
        return deployment;
    }

    public void retry(UUID deploymentId, UUID teamId) {
        find(deploymentId, teamId);
        retry(deploymentId);
    }

    public void retry(UUID deploymentId, UUID teamId, String idempotencyKey) {
        find(deploymentId, teamId);
        retry(deploymentId, idempotencyKey);
    }

    public void recordAck(UUID deploymentId, UUID agentId, boolean applied, String failureCode) {
        TeamDeployment deployment = repository.find(Objects.requireNonNull(deploymentId, "deploymentId"))
                .orElseThrow(() -> new IllegalArgumentException("team deployment does not exist"));
        requireTeamScope(deployment.teamId());
        repository.markMemberStatus(deploymentId, agentId, applied ? "SUCCEEDED" : "FAILED", failureCode);
        repository.refreshStatus(deploymentId);
    }

    /** Consumes the durable ConfigApplied event and fences stale binding acknowledgements. */
    public void recordAck(ConfigAppliedCommand command) {
        recordAck(command, command.configVersion());
    }

    public void recordAck(ConfigAppliedCommand command, long applyGeneration) {
        Objects.requireNonNull(command, "command");
        if (command.configVersion() != applyGeneration) {
            throw new TeamRevisionConflictException("ConfigApplied generation is stale");
        }
        UUID teamId = repository.findTeamIdByBinding(command.bindingId(), command.agentId())
                .orElseThrow(() -> new IllegalArgumentException("team deployment does not exist"));
        requireTeamScope(teamId);
        repository.recordConfigAppliedAck(command, applyGeneration);
    }

    private void requireTeamScope(UUID teamId) {
        if (resourceScopes == null) {
            throw new IllegalStateException("resource scope repository is required");
        }
        PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
        resourceScopes.requireVisible("TEAM", Objects.requireNonNull(teamId, "teamId"));
    }

    private void applyMember(TeamDeployment deployment, TeamRevision revision, TeamDeployment.Member member,
            String actor) {
        if (member.baseManifest() == null || member.baseManifest().isBlank()) {
            throw new IllegalArgumentException("baseManifest is required for every deployment member");
        }
        String subject = "team-revision:" + revision.teamId() + ":" + revision.revision() + ":" + member.agentId();
        EffectiveConfig effective = composer.compose(new EffectiveConfigRequest(
                UUID.nameUUIDFromBytes(member.baseManifest().getBytes(StandardCharsets.UTF_8)), member.agentId(),
                revision.teamId(), revision.revision(), deployment.id(), List.of(sha256(subject)),
                member.baseManifest(), revision.overlayJson(), member.taskOverlay() == null ? "{}" : member.taskOverlay()));
        ConfigSnapshot snapshot = snapshots.create(subject, effective.canonicalManifest(), actor,
                deployment.id().toString(), effective.provenance());
        ConfigDeploymentService.ConfigDeployment deploymentResult = deployments.deploy(member.agentId(), subject, snapshot,
                deployment.id().toString());
        repository.markMember(deployment.id(), member.agentId(), deploymentResult.binding().id(), "PENDING", null);
    }

    private void applyMemberWithoutRevision(TeamDeployment deployment, TeamDeployment.Member member, String actor) {
        String subject = "team-deployment:" + deployment.id() + ":" + member.agentId();
        ConfigSnapshot snapshot = snapshots.create(subject, member.baseManifest(), actor,
                deployment.id().toString(), null);
        ConfigDeploymentService.ConfigDeployment result = deployments.deploy(member.agentId(), subject, snapshot,
                deployment.id().toString());
        repository.markMember(deployment.id(), member.agentId(), result.binding().id(), "PENDING", null);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
