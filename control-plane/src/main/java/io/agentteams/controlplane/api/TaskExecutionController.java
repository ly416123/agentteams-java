package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.task.TaskRunQueryRepository;
import io.agentteams.controlplane.task.TaskRecoveryCheckpoint;
import io.agentteams.controlplane.task.TaskRecoveryCheckpointRepository;
import io.agentteams.controlplane.task.TaskRecoveryState;
import io.agentteams.controlplane.task.TaskRecoveryStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Scope-checked, metadata-only execution projection for the management console. */
@RestController
@RequestMapping("/api/v1/tasks")
public final class TaskExecutionController {
    private final TaskService tasks;
    private final FoundationPersistenceService persistence;
    private final TaskRunQueryRepository runs;
    private final TaskRecoveryCheckpointRepository checkpoints;
    private final TaskRecoveryStateRepository recoveryStates;

    public TaskExecutionController(TaskService tasks, FoundationPersistenceService persistence) {
        this(tasks, persistence, null, null, null);
    }

    public TaskExecutionController(TaskService tasks, FoundationPersistenceService persistence,
            TaskRunQueryRepository runs, TaskRecoveryCheckpointRepository checkpoints) {
        this(tasks, persistence, runs, checkpoints, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskExecutionController(TaskService tasks, FoundationPersistenceService persistence,
            TaskRunQueryRepository runs, TaskRecoveryCheckpointRepository checkpoints,
            TaskRecoveryStateRepository recoveryStates) {
        this.tasks = tasks;
        this.persistence = persistence;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.recoveryStates = recoveryStates;
    }

    @GetMapping("/{taskId}/execution")
    public List<ExecutionResponse> execution(@PathVariable UUID taskId) {
        tasks.get(taskId);
        return persistence.findTaskExecution(taskId).stream().map(ExecutionResponse::from).toList();
    }

    @GetMapping("/{taskId}/runs")
    public List<RunResponse> runs(@PathVariable UUID taskId) {
        tasks.get(taskId);
        if (runs == null) throw new IllegalStateException("task run query is not configured");
        return runs.findByTaskId(taskId).stream().map(RunResponse::from).toList();
    }

    @GetMapping("/{taskId}/runs/{runId}/checkpoints")
    public List<CheckpointResponse> checkpoints(@PathVariable UUID taskId, @PathVariable UUID runId) {
        tasks.get(taskId);
        if (checkpoints == null) throw new IllegalStateException("task checkpoint query is not configured");
        return checkpoints.findByRun(runId).stream().filter(value -> taskId.equals(value.taskId()))
                .map(CheckpointResponse::from).toList();
    }

    @GetMapping("/{taskId}/recovery")
    public RecoveryResponse recovery(@PathVariable UUID taskId) {
        tasks.get(taskId);
        if (recoveryStates == null) throw new IllegalStateException("task recovery query is not configured");
        return recoveryStates.findByTaskId(taskId).map(RecoveryResponse::from).orElse(null);
    }

    public record RunResponse(UUID id, UUID taskId, String status, Instant startedAt, Instant completedAt,
            Instant createdAt, Instant updatedAt, long version, String resultStatus, String resultSummary) {
        static RunResponse from(TaskRunQueryRepository.TaskRunRecord value) {
            return new RunResponse(value.id(), value.taskId(), value.status(), value.startedAt(), value.completedAt(),
                    value.createdAt(), value.updatedAt(), value.version(), value.resultStatus(), value.resultSummary());
        }
    }

    public record CheckpointResponse(UUID id, UUID taskId, UUID runId, UUID attemptId, String stepKey,
            String idempotencyKey, String status, String checkpointRef, Instant createdAt, Instant updatedAt,
            long version) {
        static CheckpointResponse from(TaskRecoveryCheckpoint value) {
            return new CheckpointResponse(value.id(), value.taskId(), value.runId(), value.attemptId(), value.stepKey(),
                    value.idempotencyKey(), value.status(), value.checkpointRef(), value.createdAt(), value.updatedAt(),
                    value.version());
        }
    }

    public record RecoveryResponse(UUID taskId, int recoveryCount, int maxRecoveryAttempts, String status,
            String lastReason, Instant nextAttemptAt, Instant lastRecoveredAt, Instant createdAt, Instant updatedAt,
            long version) {
        static RecoveryResponse from(TaskRecoveryState value) {
            return new RecoveryResponse(value.taskId(), value.recoveryCount(), value.maxRecoveryAttempts(),
                    value.status(), value.lastReason(), value.nextAttemptAt(), value.lastRecoveredAt(),
                    value.createdAt(), value.updatedAt(), value.version());
        }
    }

    public record ExecutionResponse(AttemptResponse attempt, AssignmentResponse assignment,
            LeaseResponse lease) {
        static ExecutionResponse from(FoundationPersistenceService.TaskExecutionRecord value) {
            return new ExecutionResponse(AttemptResponse.from(value.attempt()),
                    value.assignment() == null ? null : AssignmentResponse.from(value.assignment()),
                    value.lease() == null ? null : LeaseResponse.from(value.lease()));
        }
    }

    public record AttemptResponse(UUID id, UUID taskId, UUID leaseId, String phase, Instant leaseExpiresAt,
            Instant completedAt, String actor, String source, String failureCode, Instant createdAt,
            Instant updatedAt, long version) {
        static AttemptResponse from(TaskAttemptRecord value) {
            return new AttemptResponse(value.id(), value.taskId(), value.leaseId(), value.phase().name(),
                    value.leaseExpiresAt(), value.completedAt(), value.actor(), value.source(),
                    value.failureCode(), value.createdAt(), value.updatedAt(), value.version());
        }
    }

    public record AssignmentResponse(UUID id, UUID taskId, UUID attemptId, UUID agentId, String phase,
            Instant assignedAt, Instant acceptedAt, Instant releasedAt, long version) {
        static AssignmentResponse from(TaskAssignmentRecord value) {
            return new AssignmentResponse(value.id(), value.taskId(), value.attemptId(), value.agentId(),
                    value.phase().name(), value.assignedAt(), value.acceptedAt(), value.releasedAt(), value.version());
        }
    }

    public record LeaseResponse(UUID id, UUID agentId, UUID taskAttemptId, Instant acquiredAt, Instant expiresAt,
            Instant releasedAt, String status, long version) {
        static LeaseResponse from(AgentLeaseRecord value) {
            return new LeaseResponse(value.id(), value.agentId(), value.taskAttemptId(), value.acquiredAt(),
                    value.expiresAt(), value.releasedAt(), value.status(), value.version());
        }
    }
}
