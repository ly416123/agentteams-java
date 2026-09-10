package io.agentteams.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.DomainEventRecord;
import io.agentteams.controlplane.api.CursorPage;
import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.persistence.TaskListRecord;
import io.agentteams.observability.TaskMetricsPort;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.domain.task.Task;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

@Service
public final class TaskService {

    private static final String QUEUE_TASK = "QUEUE_TASK";
    private static final String CANCEL_TASK = "CANCEL_TASK";
    private static final String RETRY_TASK = "RETRY_TASK";
    private static final String PAUSE_TASK = "PAUSE_TASK";
    private static final String APPROVE_TASK = "APPROVE_TASK";
    private static final String REJECT_TASK = "REJECT_TASK";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final TaskTransitionService transitions;
    private final Clock clock;
    private final TaskMetricsPort metrics;
    private final ResourceScopeRepository resourceScopes;
    private final ResourceAuthorizationService authorization;

    public TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions) {
        this(persistence, idempotency, transitions, Clock.systemUTC(), TaskMetricsPort.noop(), null);
    }

    @Autowired
    public TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, TaskMetricsPort metrics,
            ObjectProvider<ResourceScopeRepository> scopes,
            ObjectProvider<ResourceAuthorizationService> authorization) {
        this(persistence, idempotency, transitions, Clock.systemUTC(), metrics, scopes.getIfAvailable(),
                authorization.getIfAvailable());
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock) {
        this(persistence, idempotency, transitions, clock, TaskMetricsPort.noop(), null);
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock, TaskMetricsPort metrics) {
        this(persistence, idempotency, transitions, clock, metrics, null);
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock, TaskMetricsPort metrics,
            ResourceScopeRepository resourceScopes) {
        this(persistence, idempotency, transitions, clock, metrics, resourceScopes, null);
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock, TaskMetricsPort metrics,
            ResourceScopeRepository resourceScopes, ResourceAuthorizationService authorization) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.resourceScopes = resourceScopes;
        this.authorization = authorization;
    }

    public TaskRecord create(String idempotencyKey, TaskInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String title = required(input.title(), "title");
        String description = input.description() == null ? "" : input.description();
        String actor = defaultText(input.actor(), "api");
        String source = defaultText(input.source(), "rest");
        String spec = jsonObjectOrDefault(input.specJson());
        authorizeCreate(spec);
        Instant now = clock.instant();
        String taskType = input.taskType() == null || input.taskType().isBlank()
                ? typeFromSpec(spec) : input.taskType();
        TaskRecord created = persistence.createTask(new CreateTaskCommand(key, title, description, actor, source,
                spec, now, taskType));
        bindIfAuthenticated(created.id());
        requireVisible(created.id());
        metrics.taskCreated();
        return created;
    }

    public TaskRecord get(UUID id) {
        Objects.requireNonNull(id, "id");
        TaskRecord task = persistence.findTask(id).orElseThrow(() -> new ResourceNotFoundException("task", id));
        requireVisible(task.id());
        return task;
    }

    public CursorPage<TaskListRecord> list(CursorPageRequest request, TaskListFilter filter) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(filter, "filter");
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        java.util.List<TaskListRecord> rows = persistence.inTransaction(tx -> tx.tasks().findPage(principal,
                request.position(), request.pageSize() + 1, request.direction(), filter.phase(), filter.statuses(),
                filter.teamId(), filter.workerId(), filter.actor(), filter.from(), filter.to(), filter.query(),
                filter.archiveStatus()));
        return CursorPage.fromRows(rows, request.pageSize(),
                task -> new CursorPageRequest.Position(task.updatedAt(), task.id()), clock.instant());
    }

    /** 团队维度任务统计（G02）：按 principal scope 聚合，archiveStatus 默认 ACTIVE。 */
    public java.util.Map<String, Long> stats(String archiveStatus) {
        String normalized = archiveStatus == null || archiveStatus.isBlank()
                ? "ACTIVE" : archiveStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"ARCHIVED".equals(normalized) && !"ALL".equals(normalized)) {
            throw new IllegalArgumentException("archiveStatus must be ACTIVE, ARCHIVED or ALL");
        }
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        return persistence.inTransaction(tx -> tx.tasks().countByPhase(principal, normalized));
    }

    /** D1：任务最新结果版本号；尚无结果版本时为空。 */
    public Integer latestResultSeq(UUID id) {
        get(id);
        return persistence.inTransaction(tx -> tx.taskResultVersions().latestSeq(id)).orElse(null);
    }

    public List<DomainEventRecord> events(UUID id, long after) {
        if (after < 0) throw new IllegalArgumentException("after cursor must not be negative");
        TaskRecord task = get(id);
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        return persistence.inTransaction(tx -> tx.domainEvents().findTaskEvents(principal, task.id(), after, 1000));
    }

    public record TaskListFilter(TaskPhase phase, java.util.Collection<TaskPhase> statuses, UUID teamId,
            UUID workerId, String actor, Instant from, Instant to, String query, String archiveStatus) {

        private static final String DEFAULT_ARCHIVE_STATUS = "ACTIVE";

        public TaskListFilter(TaskPhase phase, UUID teamId, UUID workerId, String actor, Instant from, Instant to,
                String query) {
            this(phase, null, teamId, workerId, actor, from, to, query, DEFAULT_ARCHIVE_STATUS);
        }

        public TaskListFilter {
            if (from != null && to != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("from must be before to");
            }
            query = query == null ? null : query.trim();
            archiveStatus = archiveStatus == null || archiveStatus.isBlank()
                    ? DEFAULT_ARCHIVE_STATUS : archiveStatus.trim().toUpperCase(java.util.Locale.ROOT);
            if (!"ACTIVE".equals(archiveStatus) && !"ARCHIVED".equals(archiveStatus)
                    && !"ALL".equals(archiveStatus)) {
                throw new IllegalArgumentException("archiveStatus must be ACTIVE, ARCHIVED or ALL");
            }
        }
    }

    /** Explicit queue operation; task creation intentionally remains in DRAFT. */
    public TaskRecord queue(UUID id, long expectedVersion, String idempotencyKey) {
        return queue(id, expectedVersion, idempotencyKey, "api");
    }

    public TaskRecord queue(UUID id, long expectedVersion, String idempotencyKey, String actor) {
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        return transition(id, TaskPhase.QUEUED, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), "service", QUEUE_TASK);
    }

    public TaskRecord cancel(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return cancel(id, expectedVersion, idempotencyKey, actor, source, null);
    }

    /** 取消（G02）：可选 reason 写入 spec 顶层 cancelReason，随任务透出取消原因。 */
    public TaskRecord cancel(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source, String reason) {
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (normalizedReason == null) {
            return transition(id, TaskPhase.CANCELLED, expectedVersion, idempotencyKey,
                    defaultText(actor, "api"), defaultText(source, "rest"), CANCEL_TASK);
        }
        TaskRecord current = get(id);
        if (!transitions.legal(current.phase(), TaskPhase.CANCELLED)) {
            throw new IllegalTaskTransitionException(current.phase(), TaskPhase.CANCELLED);
        }
        String specJson;
        try {
            specJson = JSON.writeValueAsString(cancelReasonSpec(current.specJson(), normalizedReason));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task cancel spec could not be encoded", error);
        }
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash(id.toString(), TaskPhase.CANCELLED.name(),
                Long.toString(expectedVersion), defaultText(actor, "api"), defaultText(source, "rest"),
                normalizedReason);
        return persistence.transitionTaskWithSpec(id, TaskPhase.CANCELLED, specJson, expectedVersion,
                clock.instant(), key, requestHash, CANCEL_TASK, null);
    }

    /** Schedule controller has already checked the schedule scope and run identity. */
    public TaskRecord cancelFromSchedule(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return transition(id, TaskPhase.CANCELLED, expectedVersion, idempotencyKey,
                defaultText(actor, "scheduler"), defaultText(source, "scheduled-task"), CANCEL_TASK);
    }

    public TaskRecord retry(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        return transition(id, TaskPhase.QUEUED, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), RETRY_TASK);
    }

    /** Pause toggles QUEUED to PAUSED and PAUSED back to QUEUED. */
    public TaskRecord pause(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        TaskRecord current = get(id);
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        TaskPhase target = current.phase() == TaskPhase.PAUSED ? TaskPhase.QUEUED : TaskPhase.PAUSED;
        return transition(id, target, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), PAUSE_TASK);
    }

    public TaskRecord approve(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        authorizeTask(id, ResourceAction.TASK_APPROVE);
        return updateApproval(id, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), true, APPROVE_TASK);
    }

    public TaskRecord reject(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        authorizeTask(id, ResourceAction.TASK_APPROVE);
        return updateApproval(id, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), false, REJECT_TASK);
    }

    private static final String ARCHIVE_TASK = "ARCHIVE_TASK";
    private static final String UNARCHIVE_TASK = "UNARCHIVE_TASK";
    private static final String PATCH_TASK = "PATCH_TASK";
    private static final String DELETE_TASK = "DELETE_TASK";

    /** 归档（G02 D6）：仅终态；不改 phase，只切换独立属性。 */
    public TaskRecord archive(UUID id, long expectedVersion, String idempotencyKey, String actor, String source) {
        TaskRecord current = get(id);
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        if (!current.phase().terminal()) {
            throw new IllegalArgumentException("task archive is only available for terminal tasks");
        }
        if (current.archived()) {
            throw new IllegalArgumentException("task is already archived");
        }
        return transitionArchive(id, "ARCHIVED", clock.instant(), defaultText(actor, "api"),
                expectedVersion, idempotencyKey, ARCHIVE_TASK);
    }

    public TaskRecord unarchive(UUID id, long expectedVersion, String idempotencyKey, String actor, String source) {
        TaskRecord current = get(id);
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        if (!current.archived()) {
            throw new IllegalArgumentException("task is not archived");
        }
        return transitionArchive(id, "ACTIVE", null, defaultText(actor, "api"),
                expectedVersion, idempotencyKey, UNARCHIVE_TASK);
    }

    private TaskRecord transitionArchive(UUID id, String targetStatus, java.time.Instant archivedAt,
            String actor, long expectedVersion, String idempotencyKey, String operation) {
        String requestHash = idempotency.requestHash(id.toString(), operation,
                Long.toString(expectedVersion), actor);
        return persistence.inTransaction(tx -> {
            String key = idempotency.requireKey(idempotencyKey);
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotentOperation(existing.get(), operation, requestHash, key);
                return tx.tasks().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), key, operation,
                    requestHash, "task", id, "{\"id\":\"" + id + "\"}", clock.instant(), clock.instant(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotentOperation(winner, operation, requestHash, key);
                return tx.tasks().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }
            TaskRecord updated = tx.tasks().updateArchive(id, targetStatus, archivedAt, actor, expectedVersion,
                    clock.instant());
            FoundationPersistenceService.appendEvent(tx, "task", id,
                    operation.equals(ARCHIVE_TASK) ? "TaskArchived" : "TaskUnarchived",
                    "{\"taskId\":\"" + id + "\",\"archiveStatus\":\"" + targetStatus + "\"}",
                    clock.instant(), updated.version());
            return updated;
        });
    }

    public record TaskPatchCommand(String title, String description, Integer priority,
            com.fasterxml.jackson.databind.JsonNode spec) {
    }

    /** 元数据更新（G02 D9）：白名单字段 + spec 顶层键浅合并。 */
    public TaskRecord patch(UUID id, TaskPatchCommand command, long expectedVersion, String idempotencyKey,
            String actor) {
        Objects.requireNonNull(command, "command");
        TaskRecord current = get(id);
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        if (command.title() == null && command.description() == null && command.priority() == null
                && command.spec() == null) {
            throw new IllegalArgumentException("at least one updatable field is required");
        }
        String title = command.title() == null ? current.title() : required(command.title(), "title");
        String description = command.description() == null ? current.description() : command.description();
        int priority = command.priority() == null ? current.priority() : command.priority();
        String specJson = command.spec() == null ? current.specJson()
                : mergeSpecTopLevel(current.specJson(), command.spec());
        String requestHash = idempotency.requestHash(id.toString(), PATCH_TASK, Long.toString(expectedVersion),
                title, description, Integer.toString(priority), specJson);
        return persistence.inTransaction(tx -> {
            String key = idempotency.requireKey(idempotencyKey);
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotentOperation(existing.get(), PATCH_TASK, requestHash, key);
                return tx.tasks().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), key, PATCH_TASK,
                    requestHash, "task", id, "{\"id\":\"" + id + "\"}", clock.instant(), clock.instant(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotentOperation(winner, PATCH_TASK, requestHash, key);
                return tx.tasks().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }
            TaskRecord updated = tx.tasks().updateMetadata(id, title, description, priority, specJson,
                    expectedVersion, clock.instant());
            FoundationPersistenceService.appendEvent(tx, "task", id, "TaskMetadataUpdated",
                    "{\"taskId\":\"" + id + "\"}", clock.instant(), updated.version());
            return updated;
        });
    }

    /** 受限删除（G02 D10）：仅 DRAFT 且无任何执行记录。 */
    public void delete(UUID id, String idempotencyKey, String actor) {
        TaskRecord current = get(id);
        authorizeTask(id, ResourceAction.TASK_OPERATE);
        if (current.phase() != TaskPhase.DRAFT) {
            throw new IllegalArgumentException("only draft tasks can be deleted");
        }
        persistence.inTransaction(tx -> {
            String key = idempotency.requireKey(idempotencyKey);
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotentOperation(existing.get(), DELETE_TASK, "delete:" + id, key);
                return null;
            }
            if (tx.hasTaskRun(id)) {
                throw new IllegalArgumentException("task with execution history cannot be deleted");
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), key, DELETE_TASK,
                    "delete:" + id, "task", id, "{\"id\":\"" + id + "\"}", clock.instant(), clock.instant(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                return null;
            }
            FoundationPersistenceService.appendEvent(tx, "task", id, "TaskDeleted",
                    "{\"taskId\":\"" + id + "\",\"actor\":\"" + defaultText(actor, "api") + "\"}",
                    clock.instant(), current.version());
            tx.tasks().deleteResourceScope(id);
            tx.tasks().delete(id);
            return null;
        });
    }

    private static void assertIdempotentOperation(io.agentteams.controlplane.persistence.IdempotencyKeyRecord existing,
            String operation, String requestHash, String key) {
        if (!operation.equals(existing.operation()) || !requestHash.equals(existing.requestHash())) {
            throw new io.agentteams.controlplane.persistence.IdempotencyConflictException(key, operation);
        }
    }

    /** D9：spec 顶层键浅合并——顶层键整体覆盖，null 值表示删除该键；package-private 供测试直接验证。 */
    static String mergeSpecTopLevel(String currentSpecJson, com.fasterxml.jackson.databind.JsonNode patch) {
        try {
            com.fasterxml.jackson.databind.JsonNode current = JSON.readTree(currentSpecJson == null ? "{}"
                    : currentSpecJson);
            if (current == null || !current.isObject() || patch == null || !patch.isObject()) {
                throw new IllegalArgumentException("task spec must be a JSON object");
            }
            com.fasterxml.jackson.databind.node.ObjectNode merged = (com.fasterxml.jackson.databind.node.ObjectNode) current;
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields =
                    patch.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getValue().isNull()) {
                    merged.remove(entry.getKey());
                } else {
                    merged.set(entry.getKey(), entry.getValue());
                }
            }
            return merged.toString();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task spec is invalid JSON", error);
        }
    }

    public record TaskInput(String title, String description, String specJson, String actor, String source,
            String taskType) {
        public TaskInput(String title, String description, String specJson, String actor, String source) {
            this(title, description, specJson, actor, source, null);
        }
    }

    private static String typeFromSpec(String spec) {
        try {
            JsonNode node = JSON.readTree(spec);
            String value = node == null ? null : node.path("taskType").asText(null);
            return value == null || value.isBlank() ? "NORMAL" : value;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task spec is invalid JSON", error);
        }
    }

    private TaskRecord transition(UUID id, TaskPhase target, long expectedVersion, String idempotencyKey,
            String actor, String source, String operation) {
        Objects.requireNonNull(id, "id");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        String key = idempotency.requireKey(idempotencyKey);
        TaskRecord current = get(id);
        Instant now = clock.instant();
        String requestHash = idempotency.requestHash(id.toString(), target.name(), Long.toString(expectedVersion),
                actor, source);
        if (persistence.findIdempotencyKey(key).isPresent()) {
            return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
        }

        Task domainTask = new Task(current.id(), current.phase(), current.version(), null,
                current.createdAt(), current.updatedAt(), current.actor(), current.source(),
                current.failureCode(), current.redactedFailureMessage(), Set.of());
        try {
            transitions.transition(domainTask, TaskTransitionCommand.simple(UUID.randomUUID(), expectedVersion,
                    target, now, actor, source));
        } catch (IllegalTaskTransitionException error) {
            if (persistence.findIdempotencyKey(key).isPresent()) {
                return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
            }
            throw error;
        }
        return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
    }

    private TaskRecord updateApproval(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source, boolean granted, String operation) {
        Objects.requireNonNull(id, "id");
        TaskRecord current = get(id);
        if (current.phase() != TaskPhase.DRAFT && current.phase() != TaskPhase.QUEUED
                && current.phase() != TaskPhase.PAUSED) {
            throw new IllegalArgumentException("task approval is only available before assignment");
        }
        TaskPhase target = operation.equals(REJECT_TASK) ? TaskPhase.REJECTED : current.phase();
        ObjectNode spec = approvalSpec(current.specJson(), granted);
        String specJson;
        try {
            specJson = JSON.writeValueAsString(spec);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task approval spec could not be encoded", error);
        }
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash(id.toString(), target.name(), Long.toString(expectedVersion),
                specJson, actor, source, Boolean.toString(granted));
        return persistence.transitionTaskWithSpec(id, target, specJson, expectedVersion, clock.instant(), key,
                requestHash, operation, granted ? "APPROVED" : "REJECTED");
    }

    private static ObjectNode approvalSpec(String specJson, boolean granted) {
        try {
            JsonNode root = JSON.readTree(specJson == null ? "{}" : specJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("task spec must be a JSON object");
            }
            ObjectNode object = (ObjectNode) root;
            object.put("approvalGranted", granted);
            return object;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task spec is invalid JSON", error);
        }
    }

    private static ObjectNode cancelReasonSpec(String specJson, String reason) {
        try {
            JsonNode root = JSON.readTree(specJson == null ? "{}" : specJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("task spec must be a JSON object");
            }
            ObjectNode object = (ObjectNode) root;
            object.put("cancelReason", reason);
            return object;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task spec is invalid JSON", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String jsonObjectOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("JSON object is required");
        }
        return trimmed;
    }

    private void bindIfAuthenticated(UUID resourceId) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("TASK", resourceId, principal, clock.instant()));
        }
    }

    private void requireVisible(UUID resourceId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("TASK", resourceId);
        }
    }

    private void authorizeCreate(String spec) {
        if (authorization == null) {
            return;
        }
        PrincipalContext.current().ifPresent(principal -> {
            new AuthorizationService().requireScope(principal, spec);
            authorization.require(ResourceAction.TASK_CREATE, principal.scope());
        });
    }

    private void authorizeTask(UUID id, ResourceAction action) {
        if (authorization == null) {
            return;
        }
        PrincipalContext.current().ifPresent(principal -> {
            TaskRecord task = get(id);
            new AuthorizationService().requireScope(principal, task.specJson());
            authorization.require(action, principal.scope());
        });
    }
}
