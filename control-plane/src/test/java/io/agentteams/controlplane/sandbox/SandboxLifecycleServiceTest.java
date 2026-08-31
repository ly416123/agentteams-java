package io.agentteams.controlplane.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderRef;
import io.agentteams.application.api.SandboxProvisionReceipt;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskSandboxRecord;
import io.agentteams.controlplane.persistence.TaskSandboxRepository;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.task.TaskPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class SandboxLifecycleServiceTest {

    @Test
    void usesTheVersionReturnedByMarkProvisioningForProviderUpdates() {
        Instant now = Instant.parse("2026-08-26T08:00:00Z");
        UUID sandboxId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        TaskSandboxRecord requested = sandbox(sandboxId, taskId, attemptId, 3, SandboxStatus.REQUESTED);
        TaskSandboxRecord provisioning = sandbox(sandboxId, taskId, attemptId, 4, SandboxStatus.PROVISIONING);
        TaskSandboxRecord bound = sandbox(sandboxId, taskId, attemptId, 5, SandboxStatus.PROVISIONING);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction transaction = mock(FoundationTransaction.class);
        TaskSandboxRepository repository = mock(TaskSandboxRepository.class);
        SandboxRuntimePort runtime = mock(SandboxRuntimePort.class);
        SandboxProviderRef providerRef = new SandboxProviderRef("kubernetes", "agentteams/task-sandbox", "uid");

        when(persistence.inTransaction(any())).thenAnswer(invocation ->
                ((Function<FoundationTransaction, Object>) invocation.getArgument(0)).apply(transaction));
        when(transaction.taskSandboxes()).thenReturn(repository);
        when(repository.claimRequested(eq(now), eq(1), eq("sandbox-lifecycle"), eq(now.plus(Duration.ofMinutes(2)))))
                .thenReturn(List.of(requested));
        when(repository.markProvisioning(sandboxId, 3, now)).thenReturn(provisioning);
        when(runtime.ensureProvisioned(any())).thenReturn(new SandboxProvisionReceipt(
                providerRef, SandboxProviderPhase.PROVISIONING, 1));
        when(runtime.inspect(providerRef)).thenReturn(new SandboxObservation(providerRef,
                SandboxProviderPhase.PROVISIONING, null, now.plusSeconds(300), 1, null, null));
        when(repository.updateProviderBinding(any(UUID.class), anyString(), anyString(), anyString(),
                eq(SandboxStatus.PROVISIONING), isNull(), any(Instant.class), eq(1L), isNull(), eq("{}"),
                any(Long.class), eq(now))).thenReturn(bound);
        when(repository.findById(sandboxId)).thenReturn(Optional.of(bound));
        when(repository.updateObserved(any(UUID.class), eq(SandboxStatus.PROVISIONING), isNull(), isNull(),
                any(Instant.class), eq(1L), isNull(), isNull(), isNull(), eq(5L), eq(now))).thenReturn(bound);

        new SandboxLifecycleService(persistence, runtime).provisionRequested(now, 1);

        ArgumentCaptor<SandboxProvisionCommand> command = ArgumentCaptor.forClass(SandboxProvisionCommand.class);
        verify(runtime).ensureProvisioned(command.capture());
        assertEquals(SandboxProfile.ISOLATED, command.getValue().policy().profile());
        assertEquals(Duration.ofMinutes(5), command.getValue().policy().ttl());

        ArgumentCaptor<Long> expectedVersion = ArgumentCaptor.forClass(Long.class);
        verify(repository).updateProviderBinding(any(UUID.class), anyString(), anyString(), anyString(),
                eq(SandboxStatus.PROVISIONING), isNull(), any(Instant.class), eq(1L), isNull(), eq("{}"),
                expectedVersion.capture(), eq(now));
        assertEquals(4L, expectedVersion.getValue());
    }

    @Test
    void fakeProviderIsIdempotentAndTracksLifecycleCalls() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxRequest request = SandboxRequest.of(UUID.randomUUID(), UUID.randomUUID(), SandboxProfile.ISOLATED,
                Duration.ofMinutes(5), "python", Instant.parse("2026-08-25T08:00:00Z"));

        var first = runtime.provision(request);
        var duplicate = runtime.provision(request);

        assertSame(first, duplicate);
        assertEquals(request.taskId(), first.taskId());
        assertEquals(request.attemptId(), first.attemptId());
        assertEquals(SandboxStatus.READY, runtime.inspect(first.providerSandboxId()));
        runtime.renew(first.providerSandboxId(), Instant.parse("2026-08-25T08:10:00Z"));
        assertEquals(request.taskId(), runtime.handle(first.providerSandboxId()).taskId());
        assertEquals(request.attemptId(), runtime.handle(first.providerSandboxId()).attemptId());
        runtime.terminate(first.providerSandboxId(), SandboxTerminationReason.TASK_COMPLETED);
        assertEquals(SandboxStatus.DESTROYED, runtime.inspect(first.providerSandboxId()));
        assertEquals(1, runtime.provisionCalls());
        assertEquals(1, runtime.renewCalls());
        assertEquals(1, runtime.terminateCalls());
    }

    @Test
    void parsesOnlyExplicitSandboxProfileAndKeepsTaskSpecOutOfTheRequest() {
        Instant now = Instant.parse("2026-08-25T08:00:00Z");
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        TaskRecord task = new TaskRecord(taskId, "sandbox", "description", TaskPhase.ASSIGNED, 0,
                "{\"input\":{\"secret\":\"must-not-copy\"},\"sandbox\":{"
                        + "\"profile\":\"ISOLATED\",\"template\":\"python-untrusted\",\"ttlSeconds\":120}}",
                "actor", "test", null, null, now, now, 1);
        TaskAttemptRecord attempt = new TaskAttemptRecord(attemptId, taskId, UUID.randomUUID(),
                TaskPhase.ASSIGNED, now.plusSeconds(60), null, "scheduler", "test", null, null, now, now, 1);

        SandboxRequest request = SandboxLifecycleService.requestFor(task, attempt, now).orElseThrow();

        assertEquals(SandboxProfile.ISOLATED, request.profile());
        assertEquals(Duration.ofSeconds(120), request.ttl());
        assertEquals("python-untrusted", request.template());
        assertEquals("task-attempt:" + attemptId, request.idempotencyKey());
    }

    private static TaskSandboxRecord sandbox(UUID id, UUID taskId, UUID attemptId, long version,
            SandboxStatus status) {
        Instant created = Instant.parse("2026-08-26T07:59:00Z");
        return new TaskSandboxRecord(id, taskId, attemptId, "sandbox:" + attemptId, null,
                SandboxProfile.ISOLATED, status, "template-a", null, created, created.plusSeconds(300),
                null, null, null, null, null, created, created, version);
    }
}
