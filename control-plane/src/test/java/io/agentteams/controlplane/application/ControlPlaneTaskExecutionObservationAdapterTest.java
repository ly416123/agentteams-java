package io.agentteams.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskProcessEventService;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskResultManifestService;
import io.agentteams.controlplane.webhook.WebhookDeliveryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlPlaneTaskExecutionObservationAdapterTest {
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1",
            "agent-worker");

    @Test
    void progressCreatesScopedReplayableObservationAndWebhook() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(3L);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), webhooks);

        adapter.progress(TASK_ID, RUN_ID, EVENT_ID, NOW, "corr-1", 40, "running", "started phase");

        verify(runs).ensureRun(CONTEXT, TASK_ID, RUN_ID, "RUNNING", NOW);
        verify(runs).nextSequence(RUN_ID);
        verify(process).append(any(), any());
        verify(webhooks).enqueue(any(), any(), any());
    }

    @Test
    void terminalResultPublishesManifestAndNeverLeaksSensitiveSummary() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        TaskResultManifestService results = mock(TaskResultManifestService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(4L);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, results, webhooks);

        adapter.completed(TASK_ID, RUN_ID, EVENT_ID, NOW, "corr-1", "token=should-not-leak", List.of(
                new ArtifactReference("report.txt", "objects/report.txt", "text/plain", 12,
                        "0123456789012345678901234567890123456789012345678901234567890123", "{}")));

        verify(results).publish(any(), any());
        verify(webhooks, org.mockito.Mockito.times(3)).enqueue(any(), any(), any());
        var manifest = org.mockito.ArgumentCaptor.forClass(io.agentteams.application.api.TaskResultManifest.class);
        verify(results).publish(any(), manifest.capture());
        assertThat(manifest.getValue().summary()).isEqualTo("task succeeded");
        assertThat(manifest.getValue().artifacts()).hasSize(1);
    }

    @Test
    void unscopedLegacyTaskIsNotProjected() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.empty());
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), webhooks);

        adapter.progress(TASK_ID, RUN_ID, EVENT_ID, NOW, "corr-1", 10, "running", "ignored");

        verifyNoInteractions(process, webhooks);
    }
}
