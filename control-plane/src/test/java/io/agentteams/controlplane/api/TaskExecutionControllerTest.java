package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.task.TaskRecoveryState;
import io.agentteams.controlplane.task.TaskRecoveryStateRepository;
import io.agentteams.controlplane.task.TaskRunQueryRepository;
import io.agentteams.controlplane.task.TaskRecoveryCheckpointRepository;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskExecutionControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-29T02:00:00Z");

    @Test
    void returnsAttemptAssignmentAndLeaseMetadataAfterTaskScopeCheck() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TaskService tasks = mock(TaskService.class);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        when(tasks.get(taskId)).thenReturn(TaskRecord.draft(taskId, "weekly report", "", "alice", "console", NOW));
        when(persistence.findTaskExecution(taskId)).thenReturn(List.of(new FoundationPersistenceService.TaskExecutionRecord(
                new TaskAttemptRecord(attemptId, taskId, leaseId, TaskPhase.RUNNING, NOW.plusSeconds(3600), null,
                        "alice", "console", null, null, NOW, NOW, 1),
                new TaskAssignmentRecord(assignmentId, taskId, attemptId, agentId, TaskPhase.RUNNING, NOW, NOW,
                        null, "{}", NOW, NOW, 1),
                new AgentLeaseRecord(leaseId, agentId, attemptId, NOW, NOW.plusSeconds(3600), null, "ACTIVE", NOW,
                        NOW, 1))));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskExecutionController(tasks, persistence)).build();

        mvc.perform(get("/api/v1/tasks/{taskId}/execution", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attempt.id").value(attemptId.toString()))
                .andExpect(jsonPath("$[0].assignment.id").value(assignmentId.toString()))
                .andExpect(jsonPath("$[0].lease.id").value(leaseId.toString()))
                .andExpect(jsonPath("$[0].lease.status").value("ACTIVE"));
        verify(tasks).get(eq(taskId));
    }

    @Test
    void doesNotReadExecutionMetadataWhenTaskIsOutsideCallerScope() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskService tasks = mock(TaskService.class);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        when(tasks.get(taskId)).thenThrow(new AuthorizationException("resource is outside caller project"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskExecutionController(tasks, persistence))
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        mvc.perform(get("/api/v1/tasks/{taskId}/execution", taskId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(persistence, org.mockito.Mockito.never()).findTaskExecution(taskId);
    }

    @Test
    void returnsDurableRecoveryStateAfterTaskScopeCheck() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskService tasks = mock(TaskService.class);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        TaskRunQueryRepository runs = mock(TaskRunQueryRepository.class);
        TaskRecoveryCheckpointRepository checkpoints = mock(TaskRecoveryCheckpointRepository.class);
        TaskRecoveryStateRepository recoveryStates = mock(TaskRecoveryStateRepository.class);
        when(tasks.get(taskId)).thenReturn(TaskRecord.draft(taskId, "weekly report", "", "alice", "console", NOW));
        when(recoveryStates.findByTaskId(taskId)).thenReturn(java.util.Optional.of(new TaskRecoveryState(taskId, 2,
                3, "READY", "LEASE_EXPIRED", NOW.plusSeconds(4), NOW, NOW, NOW, 2)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TaskExecutionController(tasks, persistence, runs, checkpoints, recoveryStates)).build();

        mvc.perform(get("/api/v1/tasks/{taskId}/recovery", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.recoveryCount").value(2))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.nextAttemptAt").exists());
        verify(tasks).get(eq(taskId));
    }
}
