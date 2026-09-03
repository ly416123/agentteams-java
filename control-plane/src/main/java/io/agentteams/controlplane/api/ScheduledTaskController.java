package io.agentteams.controlplane.api;

import io.agentteams.controlplane.schedule.ScheduledTaskDefinition;
import io.agentteams.controlplane.schedule.ScheduledTaskScope;
import io.agentteams.controlplane.schedule.ScheduledTaskService;
import io.agentteams.controlplane.schedule.ScheduledTaskRun;
import io.agentteams.controlplane.schedule.ScheduledTaskRunRepository;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.ProjectScopeResolver;
import io.agentteams.controlplane.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped schedule management; every trigger becomes a normal Task. */
@RestController
@RequestMapping("/api/v1/scheduled-tasks")
public final class ScheduledTaskController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final ScheduledTaskService service;
    private final ScheduledTaskRunRepository runs;
    private final TaskService tasks;
    private final ProjectScopeResolver projectScopes;

    public ScheduledTaskController(ScheduledTaskService service) {
        this(service, null, null, null);
    }

    public ScheduledTaskController(ScheduledTaskService service, ScheduledTaskRunRepository runs,
            TaskService tasks) {
        this(service, runs, tasks, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduledTaskController(ScheduledTaskService service, ScheduledTaskRunRepository runs,
            TaskService tasks, ProjectScopeResolver projectScopes) {
        this.service = service;
        this.runs = runs;
        this.tasks = tasks;
        this.projectScopes = projectScopes;
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateScheduleRequest request) {
        requireKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        String project = canonicalProject(request.tenantId(), request.projectId());
        requireCallerScope(request.tenantId(), project);
        ScheduledTaskDefinition created = service.create(request.toServiceRequest(project));
        return ResponseEntity.status(201).body(ScheduleResponse.from(created));
    }

    @GetMapping
    public List<ScheduleResponse> list(@RequestParam String organizationId, @RequestParam String tenantId,
            @RequestParam(required = false) String projectId) {
        String project = canonicalProject(tenantId, projectId);
        requireCallerScope(tenantId, project);
        return service.list(new ScheduledTaskScope(organizationId, tenantId, project)).stream()
                .map(ScheduleResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ScheduleResponse get(@PathVariable UUID id, @RequestParam String organizationId,
            @RequestParam String tenantId, @RequestParam(required = false) String projectId) {
        String project = canonicalProject(tenantId, projectId);
        requireCallerScope(tenantId, project);
        return ScheduleResponse.from(service.find(new ScheduledTaskScope(organizationId, tenantId, project), id));
    }

    @PostMapping("/{id}/pause")
    public ScheduleResponse pause(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String operationKey,
            @RequestBody ScopeRequest request) {
        requireKey(operationKey);
        requireCallerScope(request.tenantId(), canonicalProject(request.tenantId(), request.projectId()));
        return ScheduleResponse.from(service.pause(canonicalScope(request.scope()), id, operationKey));
    }

    @PostMapping("/{id}/resume")
    public ScheduleResponse resume(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String operationKey,
            @RequestBody ScopeRequest request) {
        requireKey(operationKey);
        requireCallerScope(request.tenantId(), canonicalProject(request.tenantId(), request.projectId()));
        return ScheduleResponse.from(service.resume(canonicalScope(request.scope()), id, operationKey));
    }

    @GetMapping("/{id}/runs")
    public List<ScheduleRunResponse> runs(@PathVariable UUID id, @RequestParam String organizationId,
            @RequestParam String tenantId, @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "100") int limit) {
        requireRunDependencies();
        ScheduledTaskScope scope = canonicalScope(new ScheduledTaskScope(organizationId, tenantId, projectId));
        requireCallerScope(tenantId, scope.projectId());
        service.find(scope, id);
        return runs.list(scope, id, limit).stream().map(ScheduleRunResponse::from).toList();
    }

    @GetMapping("/{id}/runs/{runId}")
    public ScheduleRunResponse run(@PathVariable UUID id, @PathVariable UUID runId,
            @RequestParam String organizationId, @RequestParam String tenantId,
            @RequestParam(required = false) String projectId) {
        requireRunDependencies();
        ScheduledTaskScope scope = canonicalScope(new ScheduledTaskScope(organizationId, tenantId, projectId));
        requireCallerScope(tenantId, scope.projectId());
        return ScheduleRunResponse.from(runs.find(scope, id, runId)
                .orElseThrow(io.agentteams.controlplane.schedule.ScheduledTaskNotFoundException::new));
    }

    @PostMapping("/{id}/runs/{runId}/cancel")
    public ScheduleRunResponse cancelRun(@PathVariable UUID id, @PathVariable UUID runId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String operationKey,
            @RequestBody RunCancelRequest request) {
        requireKey(operationKey);
        requireRunDependencies();
        if (request == null) throw new IllegalArgumentException("request body is required");
        ScheduledTaskScope scope = canonicalScope(request.scope());
        requireCallerScope(request.tenantId(), scope.projectId());
        ScheduledTaskRun current = runs.find(scope, id, runId)
                .orElseThrow(io.agentteams.controlplane.schedule.ScheduledTaskNotFoundException::new);
        if (!current.active()) return ScheduleRunResponse.from(current);
        var task = tasks.get(current.taskId());
        long version = request.expectedTaskVersion() == null ? task.version() : request.expectedTaskVersion();
        if (!task.phase().terminal()) {
            tasks.cancelFromSchedule(task.id(), version, operationKey, PrincipalContext.actorOr("scheduler"),
                    "scheduled-console");
        }
        return ScheduleRunResponse.from(runs.cancel(scope, id, runId, operationKey, Instant.now()));
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
        }
    }

    private ScheduledTaskScope canonicalScope(ScheduledTaskScope scope) {
        if (scope.projectId() == null || scope.projectId().isBlank()) return scope;
        return new ScheduledTaskScope(scope.organizationId(), scope.tenantId(),
                canonicalProject(scope.tenantId(), scope.projectId()));
    }

    private String canonicalProject(String tenantId, String projectId) {
        if (projectId == null || projectId.isBlank()) return projectId;
        if (projectScopes == null) return projectId;
        Principal principal = PrincipalContext.current().orElseThrow(
                () -> new AuthorizationException("authentication required"));
        if (!principal.scope().tenant().equals(tenantId)) {
            throw new AuthorizationException("schedule is outside the caller scope");
        }
        return projectScopes.resolve(principal, projectId).projectIdValue();
    }

    private static void requireCallerScope(String tenantId, String projectId) {
        PrincipalContext.current().ifPresent(principal -> {
            if (!principal.scope().tenant().equals(tenantId)
                    || (projectId != null && !principal.scope().project().equals(projectId))) {
                throw new AuthorizationException("schedule is outside the caller scope");
            }
        });
    }

    public record CreateScheduleRequest(String name, String organizationId, String tenantId, String projectId,
            String cronExpression, String timeZone, String title, String description, com.fasterxml.jackson.databind.JsonNode spec,
            String actor, String source) {
        ScheduledTaskService.CreateRequest toServiceRequest() {
            return toServiceRequest(projectId);
        }

        ScheduledTaskService.CreateRequest toServiceRequest(String canonicalProjectId) {
            return new ScheduledTaskService.CreateRequest(name,
                    new ScheduledTaskScope(organizationId, tenantId, canonicalProjectId), cronExpression, timeZone,
                    title, description, spec == null || spec.isNull() ? "{}" : spec.toString(), actor, source);
        }
    }

    public record ScopeRequest(String organizationId, String tenantId, String projectId) {
        ScheduledTaskScope scope() { return new ScheduledTaskScope(organizationId, tenantId, projectId); }
    }

    public record RunCancelRequest(String organizationId, String tenantId, String projectId,
            Long expectedTaskVersion) {
        ScheduledTaskScope scope() { return new ScheduledTaskScope(organizationId, tenantId, projectId); }
    }

    public record ScheduleRunResponse(UUID id, UUID scheduleId, UUID taskId, UUID executionRunId,
            Instant occurrenceAt, String status, String taskPhase, String resultStatus, String resultSummary,
            Instant createdAt, Instant updatedAt, long version) {
        static ScheduleRunResponse from(ScheduledTaskRun value) {
            return new ScheduleRunResponse(value.id(), value.scheduleId(), value.taskId(), value.executionRunId(),
                    value.occurrenceAt(), value.status().name(), value.taskPhase(), value.resultStatus(),
                    value.resultSummary(), value.createdAt(), value.updatedAt(), value.version());
        }
    }

    public record ScheduleResponse(UUID id, String name, String organizationId, String tenantId, String projectId,
            String cronExpression, String timeZone, String title, boolean enabled, Instant nextRunAt,
            Instant lastRunAt, UUID lastTaskId, long version) {
        static ScheduleResponse from(ScheduledTaskDefinition value) {
            return new ScheduleResponse(value.id(), value.name(), value.scope().organizationId(),
                    value.scope().tenantId(), value.scope().projectId(), value.cronExpression(), value.timeZone(),
                    value.title(), value.enabled(), value.nextRunAt(), value.lastRunAt(), value.lastTaskId(), value.version());
        }
    }

    private void requireRunDependencies() {
        if (runs == null || tasks == null) {
            throw new IllegalStateException("scheduled run support is not configured");
        }
    }
}
