package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.TaskService;
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

    public TaskExecutionController(TaskService tasks, FoundationPersistenceService persistence) {
        this.tasks = tasks;
        this.persistence = persistence;
    }

    @GetMapping("/{taskId}/execution")
    public List<ExecutionResponse> execution(@PathVariable UUID taskId) {
        tasks.get(taskId);
        return persistence.findTaskExecution(taskId).stream().map(ExecutionResponse::from).toList();
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
