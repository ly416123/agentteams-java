package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.agentteams.domain.task.TaskPhase;
import org.springframework.stereotype.Service;

/** Coordinates the platform-owned Leader -> Executor -> Leader task lifecycle. */
@Service
public final class TeamOrchestrationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STORED_RESULT_BYTES = 32 * 1024;

    private final FoundationPersistenceService persistence;
    private final SubtaskService subtasks;
    private final Clock clock;

    public TeamOrchestrationService(FoundationPersistenceService persistence, SubtaskService subtasks, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.subtasks = Objects.requireNonNull(subtasks, "subtasks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates Executor child tasks exactly once from a successful Leader planning result. */
    public boolean planLeaderResult(UUID taskId, String resultJson) {
        Objects.requireNonNull(taskId, "taskId");
        TaskRecord parent = persistence.findTask(taskId).orElse(null);
        if (parent == null || parent.isSubtask() || !hasTeam(parent.specJson())
                || "AGGREGATE".equals(orchestrationPhase(parent.specJson()))) {
            return false;
        }
        if (!persistence.inTransaction(tx -> tx.tasks().findByParent(taskId)).isEmpty()) {
            return markWaitingForSubtasks(taskId);
        }
        try {
            List<SubtaskService.SubtaskSpec> specs = TeamPlanParser.parse(taskId, resultJson);
            subtasks.plan(taskId, specs);
            return markWaitingForSubtasks(taskId);
        } catch (IllegalArgumentException invalidPlan) {
            return false;
        }
    }

    /** Moves a planned Team parent out of the terminal result state exactly once. */
    public boolean markWaitingForSubtasks(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return persistence.inTransaction(tx -> {
            TaskRecord current = tx.tasks().findByIdForUpdate(taskId).orElse(null);
            if (current == null || current.isSubtask() || !hasTeam(current.specJson())
                    || "AGGREGATE".equals(orchestrationPhase(current.specJson()))
                    || current.phase() != TaskPhase.SUCCEEDED
                    || tx.tasks().findByParent(taskId).isEmpty()) {
                return false;
            }
            Instant at = clock.instant();
            TaskRecord waiting = tx.tasks().updatePhase(taskId, TaskPhase.WAITING_SUBTASKS,
                    current.version(), at);
            FoundationPersistenceService.appendEvent(tx, "task", taskId, "TaskWaitingForSubtasks",
                    "{\"taskId\":\"" + taskId + "\"}", at, waiting.version());
            return true;
        });
    }

    /** Stores a bounded child result for the subsequent Leader aggregation assignment. */
    public void recordSubtaskResult(UUID taskId, String resultJson, Instant at) {
        if (taskId == null || resultJson == null || resultJson.isBlank() || at == null) return;
        persistence.inTransaction(tx -> {
            TaskRecord current = tx.tasks().findByIdForUpdate(taskId).orElse(null);
            if (current == null || !current.isSubtask()) return null;
            try {
                JsonNode parsed = JSON.readTree(current.specJson());
                ObjectNode root = parsed != null && parsed.isObject()
                        ? (ObjectNode) parsed : JSON.createObjectNode();
                String bounded = resultJson.length() > MAX_STORED_RESULT_BYTES
                        ? resultJson.substring(0, MAX_STORED_RESULT_BYTES) : resultJson;
                root.put("lastResultJson", bounded);
                TaskRecord updated = copy(current, root.toString(), at, current.version());
                tx.tasks().updateState(updated, current.version());
            } catch (Exception ignored) {
                // A child result remains available as a normal artifact even when it is not
                // suitable for aggregation input.
            }
            return null;
        });
    }

    public static boolean hasTeam(String specJson) {
        try {
            JsonNode root = JSON.readTree(specJson);
            JsonNode team = root == null ? null : root.get("teamId");
            return team != null && team.isTextual() && !team.asText().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String orchestrationPhase(String specJson) {
        try {
            JsonNode root = JSON.readTree(specJson);
            String value = root == null ? "" : root.path("orchestrationPhase").asText("");
            return value.trim().toUpperCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    static TaskRecord copy(TaskRecord current, String specJson, Instant updatedAt, long version) {
        return copy(current, specJson, updatedAt, version, current.phase());
    }

    static TaskRecord copy(TaskRecord current, String specJson, Instant updatedAt, long version,
            TaskPhase phase) {
        return new TaskRecord(current.id(), current.title(), current.description(), phase, current.priority(),
                specJson, current.actor(), current.source(), current.failureCode(), current.redactedFailureMessage(),
                current.createdAt(), updatedAt, version, current.taskType(), current.archiveStatus(),
                current.archivedAt(), current.archiveActor(), current.parentTaskId(), current.kind(),
                current.sourceConversationId());
    }
}
