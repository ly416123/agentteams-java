package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskRecord(
        UUID id,
        String title,
        String description,
        TaskPhase phase,
        int priority,
        String specJson,
        String actor,
        String source,
        String failureCode,
        String redactedFailureMessage,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String taskType,
        String archiveStatus,
        Instant archivedAt,
        String archiveActor,
        UUID parentTaskId,
        String kind) {

    public TaskRecord(UUID id, String title, String description, TaskPhase phase, int priority,
            String specJson, String actor, String source, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version) {
        this(id, title, description, phase, priority, specJson, actor, source, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, "NORMAL", "ACTIVE", null, null);
    }

    /** G02 前 14 参兼容构造器：archive 三列缺省未归档。 */
    public TaskRecord(UUID id, String title, String description, TaskPhase phase, int priority,
            String specJson, String actor, String source, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version,
            String taskType) {
        this(id, title, description, phase, priority, specJson, actor, source, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, taskType, "ACTIVE", null, null);
    }

    /** G02 时代 17 参兼容构造器：G03 新列缺省为无父任务（MAIN），既有调用点零改动。 */
    public TaskRecord(UUID id, String title, String description, TaskPhase phase, int priority,
            String specJson, String actor, String source, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version,
            String taskType, String archiveStatus, Instant archivedAt, String archiveActor) {
        this(id, title, description, phase, priority, specJson, actor, source, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, taskType, archiveStatus,
                archivedAt, archiveActor, null, "MAIN");
    }

    public TaskRecord {
        Objects.requireNonNull(id, "id");
        requireText(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(specJson, "specJson");
        requireText(actor, "actor");
        requireText(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        taskType = requireType(taskType);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (archiveStatus != null && !"ACTIVE".equals(archiveStatus) && !"ARCHIVED".equals(archiveStatus)) {
            throw new IllegalArgumentException("archiveStatus must be ACTIVE or ARCHIVED");
        }
        if (kind == null || kind.isBlank()) {
            kind = "MAIN";
        }
        if (!"MAIN".equals(kind) && !"SUBTASK".equals(kind)) {
            throw new IllegalArgumentException("kind must be MAIN or SUBTASK");
        }
        if ("SUBTASK".equals(kind) && parentTaskId == null) {
            throw new IllegalArgumentException("subtask requires parentTaskId");
        }
        if ("MAIN".equals(kind) && parentTaskId != null) {
            throw new IllegalArgumentException("MAIN task must not carry parentTaskId");
        }
    }

    /** 归档状态（D6）；本记录可能由旧代码路径构造，空值视为未归档。 */
    public String archiveStatusOrActive() {
        return archiveStatus == null ? "ACTIVE" : archiveStatus;
    }

    public boolean archived() {
        return "ARCHIVED".equals(archiveStatusOrActive());
    }

    /** G03 D6：子任务判定（kind=SUBTASK，必有 parentTaskId）。 */
    public boolean isSubtask() {
        return "SUBTASK".equals(kind);
    }

    private static String requireType(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("taskType must be a non-blank identifier");
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static TaskRecord draft(UUID id, String title, String description,
            String actor, String source, Instant now) {
        return new TaskRecord(id, title, description, TaskPhase.DRAFT, 0, "{}", actor, source,
                null, null, now, now, 0, "NORMAL", "ACTIVE", null, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
