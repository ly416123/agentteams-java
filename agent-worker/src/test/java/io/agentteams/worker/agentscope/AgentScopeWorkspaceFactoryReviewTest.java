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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class AgentScopeWorkspaceFactoryReviewTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TASK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path root;

    @Test
    void handleOwnerMustMatchRuntimeTaskAndAttempt() {
        AgentScopeWorkspaceFactory factory = factory(new RecordingSandboxRuntime(SandboxStatus.READY));
        SandboxHandle handle = handle("provider-a", OTHER_TASK_ID, "attempt-a", "sandbox://provider/a");

        assertThatThrownBy(() -> factory.resolve(task(TASK_ID, "attempt-a", "tenant-a"), context(),
                SandboxProfile.ISOLATED, Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void ownershipSurvivesFactoryRestartAndRejectsDifferentProviderForSamePath() throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace-a"));
        SandboxWorkspaceOwnershipPort ownership = new InMemorySandboxWorkspaceOwnershipPort();
        SandboxHandle first = handle("provider-a", TASK_ID, "attempt-a", workspace.toUri().toString());
        SandboxHandle second = handle("provider-b", TASK_ID, "attempt-b", workspace.toUri().toString());
        RuntimeTask firstTask = task(TASK_ID, "attempt-a", "tenant-a");

        AgentScopeWorkspaceFactory firstFactory = factory(new RecordingSandboxRuntime(SandboxStatus.READY), ownership);
        AgentScopeWorkspaceFactory.WorkspaceBinding binding = firstFactory.resolve(
                firstTask, context(), SandboxProfile.ISOLATED, Optional.of(first));
        AgentScopeWorkspaceFactory restarted = factory(new RecordingSandboxRuntime(SandboxStatus.READY), ownership);

        assertThat(restarted.resolve(firstTask, context(), SandboxProfile.ISOLATED, Optional.of(first)))
                .isEqualTo(binding);
        assertThatThrownBy(() -> restarted.resolve(task(TASK_ID, "attempt-b", "tenant-a"), context(),
                SandboxProfile.ISOLATED, Optional.of(second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownership");
    }

    @Test
    void fileUriMustStayUnderConfiguredRootAndRejectSocketAndSymlinkEscape() throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace-a"));
        Path outside = Files.createDirectories(root.getParent().resolve("outside-workspace"));
        Path symlink = root.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | java.io.IOException error) {
            Assumptions.assumeTrue(false,
                    "symbolic links are unavailable in this test environment: " + error.getMessage());
        }
        AgentScopeWorkspaceFactory factory = factory(new RecordingSandboxRuntime(SandboxStatus.READY));

        assertThat(factory.resolve(task(TASK_ID, "attempt-a", "tenant-a"), context(),
                SandboxProfile.ISOLATED, Optional.of(handle("provider-a", TASK_ID, "attempt-a",
                        workspace.toUri().toString()))).workspacePath()).contains(workspace.toRealPath());
        for (Path unsafe : new Path[] {outside, root.resolve("docker.sock"), symlink.resolve("escaped")}) {
            assertThatThrownBy(() -> factory.resolve(task(TASK_ID, "attempt-a-" + unsafe.hashCode(),
                    "tenant-a"), context(), SandboxProfile.ISOLATED,
                    Optional.of(handle("provider-" + unsafe.hashCode(), TASK_ID,
                            "attempt-a-" + unsafe.hashCode(), unsafe.toUri().toString()))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void activeValidationRechecksProviderClockScopeAndOwnership() throws Exception {
        RecordingSandboxRuntime runtime = new RecordingSandboxRuntime(SandboxStatus.READY);
        MutableClock clock = new MutableClock(NOW);
        SandboxWorkspaceOwnershipPort ownership = new InMemorySandboxWorkspaceOwnershipPort();
        Path workspace = Files.createDirectories(root.resolve("workspace-a"));
        SandboxHandle handle = new SandboxHandle("provider-a", SandboxProfile.ISOLATED, SandboxStatus.READY,
                workspace.toUri().toString(), NOW.plusSeconds(30), TASK_ID, attemptUuid("attempt-a"));
        AgentScopeWorkspaceFactory factory = new AgentScopeWorkspaceFactory(runtime, clock, root, ownership, true);
        RuntimeTask task = task(TASK_ID, "attempt-a", "tenant-a");
        AgentScopeWorkspaceFactory.WorkspaceBinding binding = factory.resolve(
                task, context(), SandboxProfile.ISOLATED, Optional.of(handle));

        factory.validateActive(binding, task, context());
        runtime.status = SandboxStatus.LOST;
        assertThatThrownBy(() -> factory.assertUsable(binding, task, context()))
                .isInstanceOf(SandboxWorkspaceException.class)
                .extracting(error -> ((SandboxWorkspaceException) error).reason())
                .isEqualTo(SandboxWorkspaceException.Reason.LOST);

        runtime.status = SandboxStatus.READY;
        clock.instant = NOW.plusSeconds(30);
        assertThatThrownBy(() -> factory.validateActive(binding, task, context()))
                .isInstanceOf(SandboxWorkspaceException.class)
                .extracting(error -> ((SandboxWorkspaceException) error).reason())
                .isEqualTo(SandboxWorkspaceException.Reason.EXPIRED);
    }

    @Test
    void sharedOwnershipRejectsCrossTenantEvenWhenTaskAndAttemptIdsAreReused() throws Exception {
        SandboxWorkspaceOwnershipPort ownership = new InMemorySandboxWorkspaceOwnershipPort();
        AgentScopeWorkspaceFactory first = factory(new RecordingSandboxRuntime(SandboxStatus.READY), ownership);
        AgentScopeWorkspaceFactory second = factory(new RecordingSandboxRuntime(SandboxStatus.READY), ownership);
        SandboxHandle handle = handle("provider-a", TASK_ID, "attempt-a", "sandbox://provider/a");
        first.resolve(task(TASK_ID, "attempt-a", "tenant-a"), context(),
                SandboxProfile.ISOLATED, Optional.of(handle));

        assertThatThrownBy(() -> second.resolve(task(TASK_ID, "attempt-a", "tenant-b"), context(),
                SandboxProfile.ISOLATED, Optional.of(handle)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownership");
    }

    private AgentScopeWorkspaceFactory factory(SandboxRuntimePort runtime) {
        return factory(runtime, new InMemorySandboxWorkspaceOwnershipPort());
    }

    private AgentScopeWorkspaceFactory factory(SandboxRuntimePort runtime,
            SandboxWorkspaceOwnershipPort ownership) {
        return new AgentScopeWorkspaceFactory(runtime, Clock.fixed(NOW, ZoneOffset.UTC), root, ownership, true);
    }

    private static SandboxHandle handle(String providerId, UUID taskId, String attemptId, String endpoint) {
        return new SandboxHandle(providerId, SandboxProfile.ISOLATED, SandboxStatus.READY, endpoint,
                NOW.plusSeconds(60), taskId, attemptUuid(attemptId));
    }

    private static UUID attemptUuid(String attemptId) {
        try {
            return UUID.fromString(attemptId);
        } catch (IllegalArgumentException error) {
            return UUID.nameUUIDFromBytes(attemptId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static RuntimeTask task(UUID taskId, String attemptId, String tenantId) {
        return new RuntimeTask(taskId, "chat", "hello", Map.of(
                "tenantId", tenantId, "projectId", "project-a", "teamId", "team-a", "agentId", "agent-a",
                "attemptId", attemptId));
    }

    private static AgentRuntimeContext context() {
        return new AgentRuntimeContext("agentscope-test", 1, Clock.fixed(NOW, ZoneOffset.UTC), ignored -> { }, Map.of());
    }

    private static final class RecordingSandboxRuntime implements SandboxRuntimePort {
        private SandboxStatus status;

        private RecordingSandboxRuntime(SandboxStatus status) {
            this.status = status;
        }

        @Override
        public SandboxHandle provision(io.agentteams.application.api.SandboxRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SandboxStatus inspect(String providerSandboxId) {
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
