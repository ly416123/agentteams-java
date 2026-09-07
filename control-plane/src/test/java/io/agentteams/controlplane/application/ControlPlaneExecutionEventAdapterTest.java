package io.agentteams.controlplane.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.TaskExecutionObservationPort;
import io.agentteams.controlplane.audit.ModelCallAuditRecorder;
import io.agentteams.controlplane.service.ExecutionEventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlPlaneExecutionEventAdapterTest {
    @Test
    void publishesTerminalSuccessToTheTaskExecutionObservationProjection() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-07T00:00:00Z");
        ExecutionEventService executionEvents = mock(ExecutionEventService.class);
        TaskExecutionObservationPort observations = mock(TaskExecutionObservationPort.class);
        ControlPlaneExecutionEventAdapter adapter = new ControlPlaneExecutionEventAdapter(
                executionEvents, ModelCallAuditRecorder.noop(), observations);
        ExecutionEventPort.ArtifactReference artifact = new ExecutionEventPort.ArtifactReference(
                "report.txt", "objects/report.txt", "text/plain", 12,
                "0123456789012345678901234567890123456789012345678901234567890123", "{}");
        ExecutionEventPort.TaskExecutionCommand command = new ExecutionEventPort.TaskExecutionCommand(
                eventId, 2, attemptId, leaseId, occurredAt, "agent-1", "gateway",
                ExecutionEventPort.ExecutionPhase.SUCCEEDED, "", "", "corr-1");

        adapter.apply(taskId, command, List.of(artifact));

        verify(executionEvents).apply(eq(taskId), any(), any());
        verify(observations).completed(eq(taskId), eq(attemptId), eq(eventId), eq(occurredAt), eq("corr-1"),
                eq(""), eq(List.of(artifact)));
    }
}
