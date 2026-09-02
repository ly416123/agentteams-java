package io.agentteams.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.persistence.TaskSandboxRecord;
import io.agentteams.controlplane.sandbox.SandboxLifecycleService;
import io.agentteams.controlplane.memory.ContextAssemblyService;
import io.agentteams.controlplane.memory.TaskMemoryContextAssembler;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.controlplane.team.TeamSchedulingPolicy;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.domain.task.TaskPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TaskAssignmentService {

    private static final Logger LOGGER = Logger.getLogger(TaskAssignmentService.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final Duration leaseDuration;
    private final TaskMetricsPort metrics;
    private final TaskMemoryContextAssembler memoryContexts;

    public TaskAssignmentService(FoundationPersistenceService persistence, Duration leaseDuration) {
        this(persistence, leaseDuration, TaskMetricsPort.noop());
    }

    public TaskAssignmentService(FoundationPersistenceService persistence, Duration leaseDuration,
            TaskMetricsPort metrics) {
        this(persistence, leaseDuration, metrics, null, null);
    }

    public TaskAssignmentService(FoundationPersistenceService persistence, Duration leaseDuration,
            TaskMetricsPort metrics, ExecutionContextResolver contextResolver,
            ContextAssemblyService contextAssembly) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.memoryContexts = contextResolver == null || contextAssembly == null ? null
                : new TaskMemoryContextAssembler(contextAssembly, contextResolver);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    public AssignmentResult queueReadyTask(UUID taskId, Instant now) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(now, "now");
        persistence.inTransaction(tx -> {
            io.agentteams.controlplane.worker.WorkerOperationService.recoverExpiredOperations(tx, now);
            return null;
        });
        AssignmentResult result = persistence.inTransaction(tx -> {
            TaskRecord queued = tx.tasks().findByIdForUpdate(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("task does not exist: " + taskId));
            if (queued.phase() != TaskPhase.QUEUED) {
                throw new IllegalStateException("task must be QUEUED: " + taskId);
            }
            AgentRecord agent = matchingAgent(tx, queued, now)
                    .orElseThrow(() -> new IllegalStateException("no READY agent matches task capabilities"));

            UUID attemptId = UUID.randomUUID();
            UUID assignmentId = UUID.randomUUID();
            UUID leaseId = UUID.randomUUID();
            Instant expiresAt = now.plus(leaseDuration);
            long assignedVersion = queued.version() + 1;
            TaskRecord assigned = new TaskRecord(queued.id(), queued.title(), queued.description(),
                    TaskPhase.ASSIGNED, queued.priority(), queued.specJson(), queued.actor(), queued.source(),
                    null, null, queued.createdAt(), now, assignedVersion);
            TaskAttemptRecord attempt = new TaskAttemptRecord(attemptId, taskId, leaseId, TaskPhase.ASSIGNED,
                    expiresAt, null, "scheduler", "control-plane", null, null, now, now, assignedVersion);
            TaskAssignmentRecord assignment = new TaskAssignmentRecord(assignmentId, taskId, attemptId, agent.id(),
                    TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
            AgentLeaseRecord lease = new AgentLeaseRecord(leaseId, agent.id(), attemptId, now, expiresAt, null,
                    "ACTIVE", now, now, 0);

            tx.tasks().updateState(assigned, queued.version());
            tx.taskAttempts().insert(attempt);
            tx.taskAssignments().insert(assignment);
            tx.agentLeases().insert(lease);
            teamId(assigned).ifPresent(team -> {
                tx.teams().linkTask(team, taskId, approvalStatus(assigned), now);
                var member = tx.teams().findActiveMember(team, agent.id())
                        .orElseThrow(() -> new IllegalStateException("team membership disappeared during assignment"));
                tx.teams().insertTaskAssignment(UUID.randomUUID(), team, taskId, agent.id(), member.id(),
                        "ASSIGNED", now);
            });
            java.util.Optional<TaskSandboxRecord> sandbox = SandboxLifecycleService.requestInTransaction(
                    tx, assigned, attempt, now);
            String assignmentPayload = taskAssignedPayload(assigned, agent, attempt, assignment, lease,
                    sandbox.orElse(null), memoryContexts == null ? null : memoryContexts.assemble(assigned));
            UUID eventId = sandbox.isPresent() ? null : FoundationPersistenceService.appendEvent(tx, "task", taskId,
                    "TaskAssigned", assignmentPayload, now, assigned.version());
        return new AssignmentResult(assigned, agent, attempt, assignment, lease, eventId);
    });
        metrics.taskAssigned();
        return result;
    }

    private static java.util.Optional<AgentRecord> matchingAgent(FoundationTransaction tx, TaskRecord task,
            Instant now) {
        try {
            JsonNode spec = OBJECT_MAPPER.readTree(task.specJson());
            JsonNode team = spec == null ? null : spec.get("teamId");
            if (team != null && team.isTextual() && !team.asText().isBlank()) {
                UUID teamId = UUID.fromString(team.asText());
                TeamRecord record = tx.teams().findByIdForUpdate(teamId)
                        .orElseThrow(() -> new IllegalStateException("team does not exist: " + teamId));
                if (!"ACTIVE".equals(record.status())) {
                    return java.util.Optional.empty();
                }
                TeamPolicyRecord policy = tx.teams().findPolicy(teamId)
                        .orElseThrow(() -> new IllegalStateException("team policy is missing: " + teamId));
                int activeAssignments = tx.teams().activeAssignmentCount(teamId);
                TeamSchedulingPolicy.AssignmentRequest request = assignmentRequest(task, spec);
                TeamSchedulingPolicy schedulingPolicy = new TeamSchedulingPolicy();
                for (TeamMemberRecord member : tx.teams().activeMembers(teamId)) {
                    AgentRecord agent = tx.agents().findByIdForUpdate(member.agentId()).orElse(null);
                    if (agent != null && tx.workerOperations().findActiveByAgentForUpdate(agent.id(), now).isPresent()) {
                        continue;
                    }
                    TeamSchedulingPolicy.Decision decision = schedulingPolicy.evaluate(policy, member, agent,
                            request, activeAssignments);
                    if (decision.allowed()) {
                        return java.util.Optional.of(agent);
                    }
                }
                return java.util.Optional.empty();
            }
            // A task without an explicit Team still has a durable TASK resource scope.
            // Never fall back to the global READY pool: project scope is the minimum
            // boundary for Worker reuse.
            return tx.agents().findReadyMatchingInTaskProject(task.specJson(), task.id(), now);
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("task spec cannot be parsed for assignment", error);
        }
    }

    private static TeamSchedulingPolicy.AssignmentRequest assignmentRequest(TaskRecord task, JsonNode spec) {
        java.util.List<String> required = new java.util.ArrayList<>();
        JsonNode values = spec.get("requiredCapabilities");
        if (values != null && values.isArray()) {
            values.elements().forEachRemaining(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    required.add(value.asText());
                }
            });
        }
        return new TeamSchedulingPolicy.AssignmentRequest(task.id(), required,
                spec.path("approvalGranted").asBoolean(false));
    }

    private static String approvalStatus(TaskRecord task) {
        try {
            JsonNode spec = OBJECT_MAPPER.readTree(task.specJson());
            return spec.path("approvalGranted").asBoolean(false) ? "APPROVED" : "NOT_REQUIRED";
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("task spec cannot be parsed for approval status", error);
        }
    }

    private static java.util.Optional<UUID> teamId(TaskRecord task) {
        try {
            JsonNode spec = OBJECT_MAPPER.readTree(task.specJson());
            JsonNode team = spec == null ? null : spec.get("teamId");
            if (team != null && team.isTextual() && !team.asText().isBlank()) {
                return java.util.Optional.of(UUID.fromString(team.asText()));
            }
            return java.util.Optional.empty();
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("task spec cannot be parsed for team assignment", error);
        }
    }

    public int recoverExpiredLeases(Instant now) {
        Objects.requireNonNull(now, "now");
        // 每轮用一次无锁快照选出过期租约,再对每个租约单独开事务执行回收:
        // 单个租约的乐观锁冲突/集成失败整体回滚(租约保持 ACTIVE),不影响其他租约,
        // 下一轮恢复循环再重试失败的那个。
        java.util.List<UUID> leaseIds = persistence.inTransaction(tx -> tx.expiredActiveLeaseIds(now));
        int recovered = 0;
        for (UUID leaseId : leaseIds) {
            try {
                boolean reclaimed = persistence.inTransaction(tx -> {
                    FoundationTransaction.ReclaimOutcome outcome = tx.reclaimAttempt(leaseId, now,
                            "LEASE_EXPIRED", "TaskLeaseExpired", UUID.randomUUID());
                    if (!outcome.reclaimed()) {
                        return false;
                    }
                    TaskRecord task = tx.tasks().findById(outcome.taskId()).orElseThrow();
                    teamId(task).ifPresent(team -> tx.teams().releaseTaskAssignment(team, task.id(), now));
                    return true;
                });
                if (reclaimed) {
                    recovered++;
                }
            } catch (Exception error) {
                LOGGER.log(Level.WARNING, "Lease recovery skipped for lease {0}; it will be retried next round",
                        keyMessage(leaseId, error));
            }
        }
        for (int i = 0; i < recovered; i++) {
            metrics.taskLeaseExpired();
        }
        return recovered;
    }

    private static Object[] keyMessage(UUID leaseId, Exception error) {
        return new Object[] {leaseId + ": " + error.getClass().getSimpleName() + ": " + error.getMessage()};
    }

    public java.util.List<UUID> queuedTaskIds(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return persistence.inTransaction(tx -> tx.tasks().findIdsByPhase(TaskPhase.QUEUED, limit));
    }

    public static String taskAssignedPayload(TaskRecord task, AgentRecord agent, TaskAttemptRecord attempt,
            TaskAssignmentRecord assignment, AgentLeaseRecord lease, TaskSandboxRecord sandbox) {
        return taskAssignedPayload(task, agent, attempt, assignment, lease, sandbox, null);
    }

    public static String taskAssignedPayload(TaskRecord task, AgentRecord agent, TaskAttemptRecord attempt,
            TaskAssignmentRecord assignment, AgentLeaseRecord lease, TaskSandboxRecord sandbox,
            String memoryContextJson) {
        try {
            JsonNode spec = OBJECT_MAPPER.readTree(task.specJson());
            if (spec == null || !spec.isObject()) {
                throw new IllegalArgumentException("task spec must be a JSON object");
            }
            ObjectNode payload = OBJECT_MAPPER.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("agentId", agent.id().toString());
            payload.put("attemptId", attempt.id().toString());
            payload.put("assignmentId", assignment.id().toString());
            payload.put("leaseId", lease.id().toString());
            payload.put("expectedVersion", task.version());
            payload.set("spec", spec);
            payload.put("taskType", textOrDefault(spec, "taskType", "generic"));
            JsonNode input = spec.get("inputJson");
            if (input == null) {
                input = spec.get("input");
            }
            payload.set("inputJson", input == null ? OBJECT_MAPPER.createObjectNode() : input);
            JsonNode capabilities = spec.get("requiredCapabilities");
            ArrayNode capabilityArray = OBJECT_MAPPER.createArrayNode();
            if (capabilities != null && capabilities.isArray()) {
                capabilityArray.addAll((ArrayNode) capabilities);
            }
            payload.set("requiredCapabilities", capabilityArray);
            payload.put("leaseExpiresAt", lease.expiresAt().toString());
            if (memoryContextJson != null && !memoryContextJson.isBlank()) {
                payload.set("memoryContext", OBJECT_MAPPER.readTree(memoryContextJson));
            }
            if (sandbox != null) {
                ObjectNode sandboxNode = OBJECT_MAPPER.createObjectNode();
                sandboxNode.put("id", sandbox.id().toString());
                sandboxNode.put("providerSandboxId", sandbox.providerSandboxId());
                sandboxNode.put("profile", sandbox.profile().name());
                sandboxNode.put("status", sandbox.status().name());
                sandboxNode.put("endpointRef", sandbox.endpointRef());
                sandboxNode.put("expiresAt", sandbox.expiresAt().toString());
                sandboxNode.put("ownerTaskId", sandbox.taskId().toString());
                sandboxNode.put("ownerAttemptId", sandbox.attemptId().toString());
                payload.set("sandbox", sandboxNode);
            }
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalArgumentException("task spec cannot be serialized for assignment", error);
        }
    }

    private static String textOrDefault(JsonNode object, String field, String fallback) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    public record AssignmentResult(TaskRecord task, AgentRecord agent, TaskAttemptRecord attempt,
            TaskAssignmentRecord assignment, AgentLeaseRecord lease, UUID eventId) {
    }
}
