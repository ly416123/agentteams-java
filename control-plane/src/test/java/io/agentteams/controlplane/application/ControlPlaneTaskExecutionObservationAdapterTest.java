package io.agentteams.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskProcessEventService;
import io.agentteams.controlplane.task.TaskDecisionRecordService;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskResultManifestService;
import io.agentteams.controlplane.task.TaskTreeService;
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

    @Test
    void observedPersistsWhitelistedToolEventsForRequester() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(5L);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), webhooks);

        UUID eventId = UUID.randomUUID();
        adapter.observed(TASK_ID, RUN_ID, eventId, NOW, "corr-1", "tool.called", "{\"tool\":\"web_search\"}");

        var events = org.mockito.ArgumentCaptor.forClass(io.agentteams.application.api.TaskProcessEvent.class);
        verify(process).append(any(), events.capture());
        assertThat(events.getValue().eventType()).isEqualTo("tool.called");
        assertThat(events.getValue().visibility())
                .isEqualTo(io.agentteams.application.api.TaskEventVisibility.REQUESTER);
        assertThat(events.getValue().eventId()).isEqualTo(eventId);
    }

    @Test
    void observedDropsUnknownEventTypeAndOversizedPayload() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        // 公理二：入口二次校验——白名单之外与超 4KB 的载荷一律丢弃。
        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "reasoning", "{\"text\":\"secret\"}");
        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "tool.called", "x".repeat(4097));

        verify(process, never()).append(any(), any());
    }

    @Test
    void observedDropsPersistenceFailuresWithoutThrowing() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(1L);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(process).append(any(), any());
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        // 公理一：落库被拒按丢弃处理，绝不外推毒化承载终态事件的消费者。
        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "tool.called", "{}");

        verify(process).append(any(), any());
    }

    @Test
    void observedDropsNonJsonPayload() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        adapter.observed(TASK_ID, RUN_ID, UUID.randomUUID(), NOW, "corr-1", "tool.called", "not-json");

        verify(process, never()).append(any(), any());
    }

    @Test
    void observedDropsUnscopedTaskWithoutThrowing() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.empty());
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), mock(WebhookDeliveryService.class));

        adapter.observed(TASK_ID, UUID.randomUUID(), UUID.randomUUID(), NOW, "corr-1", "tool.called", "{}");

        verify(process, never()).append(any(), any());
    }

    @Test
    void firstWorkerAcceptanceProjectsManagerPlanIntoTheActualRun() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventService process = mock(TaskProcessEventService.class);
        TaskTreeService tree = mock(TaskTreeService.class);
        TaskDecisionRecordService decisions = mock(TaskDecisionRecordService.class);
        WebhookDeliveryService webhooks = mock(WebhookDeliveryService.class);
        UUID sessionId = UUID.randomUUID();
        when(runs.contextForTask(TASK_ID)).thenReturn(Optional.of(CONTEXT));
        when(runs.nextSequence(RUN_ID)).thenReturn(0L, 1L);
        when(runs.planningForTask(TASK_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskPlanningSnapshot("Build report", "Generate a report",
                        "manager", "{\"taskType\":\"manager-request\",\"managerSessionId\":\""
                                + sessionId + "\",\"requiredCapabilities\":[\"java\"]}")));
        ControlPlaneTaskExecutionObservationAdapter adapter = new ControlPlaneTaskExecutionObservationAdapter(
                runs, process, mock(TaskResultManifestService.class), webhooks, tree, decisions);

        adapter.accepted(TASK_ID, RUN_ID, EVENT_ID, NOW, "corr-manager");

        verify(tree).upsert(org.mockito.ArgumentMatchers.eq(CONTEXT), org.mockito.ArgumentMatchers.eq(RUN_ID), any());
        verify(decisions).append(org.mockito.ArgumentMatchers.eq(CONTEXT), any());
        verify(process, org.mockito.Mockito.times(2)).append(any(), any());
        verify(webhooks, org.mockito.Mockito.times(2)).enqueue(any(), any(), any());
    }
}
