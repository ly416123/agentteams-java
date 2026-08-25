package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentScopeWorkspaceFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT = "tenant-a";
    private static final String PROJECT = "project-a";
    private static final String TEAM = "team-a";
    private static final String AGENT = "agent-a";

    @Test
    void noneUsesAnExplicitNonSandboxBindingWithoutInspectingOrForgingAHandle() {
        RecordingSandboxRuntime runtime = new RecordingSandboxRuntime(SandboxStatus.READY);
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(runtime, fixedClock());

        AgentScopeWorkspaceFactory.WorkspaceBinding binding = factory.resolve(
                task("attempt-a"), context(Map.of()), Optional.empty());

        assertThat(binding.profile()).isEqualTo(SandboxProfile.NONE);
        assertThat(binding.sandboxId()).isEmpty();
        assertThat(binding.workspacePath()).isEmpty();
        assertThat(binding.expiresAt()).isEmpty();
        assertThat(binding.scopeId()).doesNotContain(TENANT, PROJECT, TEAM, AGENT);
        assertThat(runtime.inspectCalls).isZero();
    }

    @Test
    void isolatedRequiresAReadyOrRunningSandboxHandle() {
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.READY), fixedClock());

        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of()),
                SandboxProfile.ISOLATED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SandboxHandle");
        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of()),
                Optional.of(handle(SandboxProfile.ISOLATED, SandboxStatus.FAILED,
                        "sandbox://provider/sandbox-a", NOW.plusSeconds(60)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void sandboxStatusIsInspectedAndLostOrExpiredIsRejected() {
        RecordingSandboxRuntime runtime = new RecordingSandboxRuntime(SandboxStatus.LOST);
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(runtime, fixedClock());

        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of()),
                Optional.of(handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                        "sandbox://provider/sandbox-a", NOW.plusSeconds(60)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOST");

        runtime.status = SandboxStatus.EXPIRED;
        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of()),
                Optional.of(handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                        "sandbox://provider/sandbox-a", NOW.plusSeconds(60)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void expiredHandleIsRejectedUsingTheInjectedClock() {
        MutableClock clock = new MutableClock(NOW);
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.READY), clock);
        SandboxHandle handle = handle(SandboxProfile.HARDENED, SandboxStatus.READY,
                "sandbox://provider/sandbox-a", NOW.plusSeconds(30));

        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of()), Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void onlyAbsoluteFileUrisWithoutTraversalOrProviderSocketsBecomePaths() {
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.READY), fixedClock());

        AgentScopeWorkspaceFactory.WorkspaceBinding fileBinding = factory.resolve(
                task("attempt-a"), context(Map.of()), Optional.of(handle(
                        SandboxProfile.ISOLATED, SandboxStatus.READY, "file:///srv/sandboxes/attempt-a", NOW.plusSeconds(60))));
        assertThat(fileBinding.workspacePath()).contains(java.nio.file.Path.of("/srv/sandboxes/attempt-a"));

        for (String endpoint : new String[] {
                "file:relative/workspace",
                "file:///srv/sandboxes/../host",
                "file://host/srv/sandbox",
                "docker://var/run/docker.sock",
                "k8s://api/v1/pods/sandbox",
                "/srv/host-path"
        }) {
            assertThatThrownBy(() -> factory.resolve(task("attempt-" + endpoint.hashCode()), context(Map.of()),
                    Optional.of(handle(SandboxProfile.ISOLATED, SandboxStatus.READY, endpoint, NOW.plusSeconds(60)))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void providerNeutralSandboxUriIsAcceptedWithoutPretendingItIsAHostPath() {
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.RUNNING), fixedClock());

        AgentScopeWorkspaceFactory.WorkspaceBinding binding = factory.resolve(
                task("attempt-a"), context(Map.of()), Optional.of(handle(
                        SandboxProfile.HARDENED, SandboxStatus.RUNNING,
                        "sandbox://provider/sandbox-a", NOW.plusSeconds(60))));

        assertThat(binding.workspacePath()).isEmpty();
        assertThat(binding.sandboxId()).isPresent();
    }

    @Test
    void sameTaskAttemptIsStableAcrossRecoveryButDifferentAttemptGetsAnotherBinding() {
        SandboxRuntimePort runtime = new RecordingSandboxRuntime(SandboxStatus.READY);
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(runtime, fixedClock());
        SandboxHandle first = handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                "file:///srv/sandboxes/attempt-a", NOW.plusSeconds(60));
        SandboxHandle second = handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                "file:///srv/sandboxes/attempt-b", NOW.plusSeconds(60));

        AgentScopeWorkspaceFactory.WorkspaceBinding recovered = factory.resolve(
                task("attempt-a"), context(Map.of()), Optional.of(first));
        AgentScopeWorkspaceFactory.WorkspaceBinding restarted = new AgentScopeWorkspaceFactory(runtime, fixedClock())
                .resolve(task("attempt-a"), context(Map.of()), Optional.of(first));
        AgentScopeWorkspaceFactory.WorkspaceBinding retry = factory.resolve(
                task("attempt-b"), context(Map.of()), Optional.of(second));

        assertThat(restarted).isEqualTo(recovered);
        assertThat(retry.scopeId()).isNotEqualTo(recovered.scopeId());
        assertThat(retry.sandboxId()).isNotEqualTo(recovered.sandboxId());
    }

    @Test
    void oldAttemptCannotReuseItsSandboxForAnotherAttempt() {
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.READY), fixedClock());
        SandboxHandle handle = handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                "sandbox://provider/sandbox-a", NOW.plusSeconds(60));

        factory.resolve(task("attempt-a"), context(Map.of()), Optional.of(handle));

        assertThatThrownBy(() -> factory.resolve(task("attempt-b"), context(Map.of()), Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void scopeMetadataMustAgreeWithTheRuntimeTaskAndCannotCrossTenantOrTeam() {
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(
                new RecordingSandboxRuntime(SandboxStatus.READY), fixedClock());
        SandboxHandle handle = handle(SandboxProfile.ISOLATED, SandboxStatus.READY,
                "sandbox://provider/sandbox-a", NOW.plusSeconds(60));

        assertThatThrownBy(() -> factory.resolve(task("attempt-a"), context(Map.of("tenantId", "tenant-b")),
                Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");

        factory.resolve(task("attempt-a"), context(Map.of()), Optional.of(handle));
        RuntimeTask otherTeam = task("attempt-a");
        otherTeam = new RuntimeTask(TASK_ID, "chat", "hello", Map.of(
                "tenantId", TENANT, "projectId", PROJECT, "teamId", "team-b", "agentId", AGENT,
                "attemptId", "attempt-a"));
        RuntimeTask crossTask = otherTeam;
        assertThatThrownBy(() -> factory.resolve(crossTask, context(Map.of()), Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    private static SandboxHandle handle(SandboxProfile profile, SandboxStatus status,
            String endpointRef, Instant expiresAt) {
        return new SandboxHandle("provider-" + endpointRef.hashCode(), profile, status, endpointRef, expiresAt);
    }

    private static RuntimeTask task(String attemptId) {
        return new RuntimeTask(TASK_ID, "chat", "hello", Map.of(
                "tenantId", TENANT,
                "projectId", PROJECT,
                "teamId", TEAM,
                "agentId", AGENT,
                "attemptId", attemptId));
    }

    private static AgentRuntimeContext context(Map<String, String> configuration) {
        return new AgentRuntimeContext("agentscope-test", 1, fixedClock(), ignored -> { }, configuration);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class RecordingSandboxRuntime implements SandboxRuntimePort {
        private SandboxStatus status;
        private int inspectCalls;

        private RecordingSandboxRuntime(SandboxStatus status) {
            this.status = status;
        }

        @Override
        public SandboxHandle provision(io.agentteams.application.api.SandboxRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SandboxStatus inspect(String providerSandboxId) {
            inspectCalls++;
            return status;
        }

        @Override
        public void renew(String providerSandboxId, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void terminate(String providerSandboxId, SandboxTerminationReason reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
