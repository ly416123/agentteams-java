package io.agentteams.controlplane.team;

import io.agentteams.controlplane.config.EffectiveConfig;
import io.agentteams.controlplane.config.EffectiveConfigComposer;
import io.agentteams.controlplane.config.EffectiveConfigRequest;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import java.time.Clock;
import java.time.Instant;
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
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.revisions = revisions;
    }

    public TeamDeployment deploy(TeamRevision revision, List<TeamDeployment.Member> members,
            String actor, String idempotencyKey) {
        Objects.requireNonNull(revision, "revision");
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("members must not be empty");
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
        TeamDeployment deployment = repository.find(Objects.requireNonNull(deploymentId, "deploymentId"))
                .orElseThrow(() -> new IllegalArgumentException("team deployment does not exist"));
        List<TeamDeployment.Member> failed = repository.failedMembers(deployment.id());
        if (failed.isEmpty()) throw new IllegalArgumentException("team deployment has no failed members");
        repository.markRetrying(deployment.id(), failed.stream().map(TeamDeployment.Member::agentId).toList(), clock.instant());
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

    public void recordAck(UUID deploymentId, UUID agentId, boolean applied, String failureCode) {
        repository.markMemberStatus(deploymentId, agentId, applied ? "SUCCEEDED" : "FAILED", failureCode);
        repository.refreshStatus(deploymentId);
    }

    private void applyMember(TeamDeployment deployment, TeamRevision revision, TeamDeployment.Member member,
            String actor) {
        if (member.baseManifest() == null || member.baseManifest().isBlank()) return;
        EffectiveConfig effective = composer.compose(new EffectiveConfigRequest(member.agentId(), revision.teamId(),
                revision.revision(), null, member.baseManifest(), revision.overlayJson(),
                member.taskOverlay() == null ? "{}" : member.taskOverlay()));
        String subject = "team-revision:" + revision.teamId() + ":" + revision.revision() + ":" + member.agentId();
        ConfigSnapshot snapshot = snapshots.create(subject, effective.canonicalManifest(), actor);
        ConfigDeploymentService.ConfigDeployment deploymentResult = deployments.deploy(member.agentId(), subject, snapshot);
        repository.markMember(deployment.id(), member.agentId(), deploymentResult.binding().id(), "PENDING", null);
    }

    private void applyMemberWithoutRevision(TeamDeployment deployment, TeamDeployment.Member member, String actor) {
        String subject = "team-deployment:" + deployment.id() + ":" + member.agentId();
        ConfigSnapshot snapshot = snapshots.create(subject, member.baseManifest(), actor);
        ConfigDeploymentService.ConfigDeployment result = deployments.deploy(member.agentId(), subject, snapshot);
        repository.markMember(deployment.id(), member.agentId(), result.binding().id(), "PENDING", null);
    }
}
