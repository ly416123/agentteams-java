package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;

class TeamDeploymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void partialFailureRetryOnlyReleasesFailedMemberBinding() {
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID stableAgent = UUID.randomUUID();
        UUID failedAgent = UUID.randomUUID();
        TeamRevision revision = new TeamRevision(teamId, 8, stableAgent, "{}", "digest",
                TeamRevisionStatus.PUBLISHED, null, "alice", NOW, 1, List.of(stableAgent, failedAgent));
        TeamDeployment deployment = TeamDeployment.create(UUID.randomUUID(), teamId, 8,
                List.of(new TeamDeployment.Member(stableAgent), new TeamDeployment.Member(failedAgent)), NOW);
        when(repository.create(any())).thenReturn(deployment);
        when(repository.find(any())).thenReturn(java.util.Optional.of(deployment));
        UUID failedBinding = UUID.randomUUID();
        when(repository.failedMembers(deployment.id())).thenReturn(List.of(new TeamDeployment.Member(failedAgent,
                "{}", "{}", failedBinding, "FAILED", "TEMPORARY_FAILURE")));
        TeamDeploymentService service = new TeamDeploymentService(repository, snapshots, deployments, NOW);

        service.retry(deployment.id());

        verify(repository).markRetrying(deployment.id(), List.of(failedAgent), NOW);
        verify(deployments).retry(failedBinding);
        verify(snapshots, never()).create(any(), any(), any());
        assertThat(deployment.members()).extracting(TeamDeployment.Member::agentId)
                .containsExactly(stableAgent, failedAgent);
    }

    @Test
    void failedMemberDoesNotPreventStableMemberFromBeingPublished() {
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID stableAgent = UUID.randomUUID();
        UUID failedAgent = UUID.randomUUID();
        TeamRevision revision = new TeamRevision(teamId, 8, stableAgent, "{}", "digest",
                TeamRevisionStatus.PUBLISHED, null, "alice", NOW, 1, List.of(stableAgent, failedAgent));
        TeamDeployment aggregate = TeamDeployment.create(UUID.randomUUID(), teamId, 8,
                List.of(new TeamDeployment.Member(stableAgent, "{}", "{}"),
                        new TeamDeployment.Member(failedAgent, "{}", "{}")), NOW, "deploy-key");
        ConfigSnapshot snapshot = new ConfigSnapshot(UUID.randomUUID(), "subject", 1, "{}", "sha", "alice", NOW);
        ConfigBindingRecord binding = new ConfigBindingRecord(UUID.randomUUID(), "subject", stableAgent,
                snapshot.id(), NOW);
        when(repository.findByIdempotencyKey(teamId, "deploy-key")).thenReturn(java.util.Optional.empty());
        when(repository.create(any())).thenReturn(aggregate);
        when(repository.find(aggregate.id())).thenReturn(java.util.Optional.of(aggregate));
        when(snapshots.create(any(), any(), any(), any(), any())).thenReturn(snapshot);
        when(deployments.deploy(eq(stableAgent), eq("team-revision:" + teamId + ":8:" + stableAgent), eq(snapshot), any()))
                .thenReturn(new ConfigDeploymentService.ConfigDeployment(binding, snapshot, UUID.randomUUID()));
        when(deployments.deploy(eq(failedAgent), eq("team-revision:" + teamId + ":8:" + failedAgent), eq(snapshot), any()))
                .thenThrow(new IllegalStateException("temporary"));
        TeamDeploymentService service = new TeamDeploymentService(repository, snapshots, deployments, NOW);

        service.deploy(revision, aggregate.members(), "alice", "deploy-key");

        verify(repository).markMember(aggregate.id(), stableAgent, binding.id(), "PENDING", null);
        verify(repository).markMember(org.mockito.ArgumentMatchers.eq(aggregate.id()),
                org.mockito.ArgumentMatchers.eq(failedAgent), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("FAILED"), org.mockito.ArgumentMatchers.eq("IllegalStateException"));
        verify(repository).refreshStatus(aggregate.id());
    }

    @Test
    void rejectsDeploymentOfDraftRevisionAndFailsMembersWithoutBaseManifest() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TeamRevision draft = new TeamRevision(teamId, 2, agentId, "{}", "digest", TeamRevisionStatus.DRAFT,
                null, "alice", NOW, 0, List.of(agentId));
        TeamDeploymentService service = new TeamDeploymentService(repository, snapshots, deployments, NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deploy(draft,
                List.of(new TeamDeployment.Member(agentId)), "alice", "key"))
                .isInstanceOf(TeamRevisionConflictException.class)
                .hasMessageContaining("PUBLISHED");
        verify(repository, never()).create(any());
    }

    @Test
    void missingBaseManifestIsRecordedAsFailureAndAggregateConverges() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TeamRevision published = new TeamRevision(teamId, 2, agentId, "{}", "digest",
                TeamRevisionStatus.PUBLISHED, null, "alice", NOW, 1, List.of(agentId));
        TeamDeployment aggregate = TeamDeployment.create(UUID.randomUUID(), teamId, 2,
                List.of(new TeamDeployment.Member(agentId)), NOW, "deploy-key");
        when(repository.findByIdempotencyKey(teamId, "deploy-key")).thenReturn(java.util.Optional.empty());
        when(repository.create(any())).thenReturn(aggregate);
        when(repository.find(aggregate.id())).thenReturn(java.util.Optional.of(aggregate));

        new TeamDeploymentService(repository, snapshots, deployments, NOW)
                .deploy(published, aggregate.members(), "alice", "deploy-key");

        verify(repository).markMember(aggregate.id(), agentId, null, "FAILED", "IllegalArgumentException");
        verify(repository).refreshStatus(aggregate.id());
        verify(snapshots, never()).create(any(), any(), any());
    }

    @Test
    void retryCarriesAndPersistsItsIdempotencyKey() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID deploymentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        TeamDeployment deployment = TeamDeployment.create(deploymentId, teamId, 2,
                List.of(new TeamDeployment.Member(agentId)), NOW, "deploy-key");
        when(repository.find(deploymentId)).thenReturn(java.util.Optional.of(deployment));
        when(repository.failedMembers(deploymentId)).thenReturn(List.of(new TeamDeployment.Member(agentId, "{}", "{}",
                bindingId, "FAILED", "TEMPORARY_FAILURE")));
        when(repository.claimRetry(eq(deploymentId), any(), eq(0L), eq("retry-key"), any())).thenReturn(true);

        new TeamDeploymentService(repository, snapshots, deployments, NOW).retry(deploymentId, "retry-key");

        verify(repository).claimRetry(eq(deploymentId), eq(List.of(agentId)), eq(0L), eq("retry-key"), any());
        verify(deployments).retry(bindingId);
    }

    @Test
    void retryClaimsFailedMembersWithDeploymentVersionCas() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        UUID deploymentId = UUID.randomUUID();
        TeamDeployment deployment = TeamDeployment.create(deploymentId, UUID.randomUUID(), 2,
                List.of(new TeamDeployment.Member(UUID.randomUUID(), "{}", "{}")), NOW, "deploy-key");
        when(repository.find(deploymentId)).thenReturn(java.util.Optional.of(deployment));
        when(repository.failedMembers(deploymentId)).thenReturn(List.of(deployment.members().get(0)));
        when(repository.claimRetry(eq(deploymentId), any(), eq(0L), eq("retry-key"), any())).thenReturn(true);

        new TeamDeploymentService(repository, snapshots, deployments, NOW).retry(deploymentId, "retry-key");

        verify(repository).claimRetry(eq(deploymentId), any(), eq(0L), eq("retry-key"), any());
    }

    @Test
    void configAppliedAckIsFencedByDeploymentRevision() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        TeamDeploymentService service = new TeamDeploymentService(repository, snapshots, deployments, NOW);
        ConfigAppliedCommand command = new ConfigAppliedCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, true, "", NOW, "worker");

        service.recordAck(command);

        verify(repository).recordConfigAppliedAck(command, 1L);
    }

    @Test
    void configAppliedAckRequiresMatchingEventAndGeneration() {
        TeamDeploymentRepository repository = mock(TeamDeploymentRepository.class);
        ConfigSnapshotService snapshots = mock(ConfigSnapshotService.class);
        ConfigDeploymentService deployments = mock(ConfigDeploymentService.class);
        TeamDeploymentService service = new TeamDeploymentService(repository, snapshots, deployments, NOW);
        ConfigAppliedCommand command = new ConfigAppliedCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, true, "", NOW, "worker", "correlation");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordAck(command, 4))
                .isInstanceOf(TeamRevisionConflictException.class).hasMessageContaining("stale");
    }
}
