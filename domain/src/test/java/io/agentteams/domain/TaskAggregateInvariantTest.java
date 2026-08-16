package io.agentteams.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentteams.domain.task.FailureInfo;
import io.agentteams.domain.task.Task;
import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskAggregateInvariantTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(1);
    private static final Instant EXPIRY = CREATED.plusSeconds(60);

    @Test
    void rejectsTaskAttemptWithAnotherTaskIdVersionOrPhase() {
        UUID taskId = UUID.randomUUID();
        TaskAttempt assigned = attempt(taskId, TaskPhase.ASSIGNED, 1, null);

        assertThrows(IllegalArgumentException.class, () -> new Task(UUID.randomUUID(), TaskPhase.ASSIGNED,
                1, assigned, CREATED, UPDATED, "actor", "source", null, null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.ASSIGNED,
                2, assigned, CREATED, UPDATED, "actor", "source", null, null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.ACCEPTED,
                1, assigned, CREATED, UPDATED, "actor", "source", null, null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.QUEUED,
                1, assigned, CREATED, UPDATED, "actor", "source", null, null, Set.of()));
        assertDoesNotThrow(() -> new Task(taskId, TaskPhase.ASSIGNED, 1, assigned,
                CREATED, UPDATED, "actor", "source", null, null, Set.of()));
    }

    @Test
    void enforcesTaskFailureFieldsAndCancelledAttemptShape() {
        UUID taskId = UUID.randomUUID();
        TaskAttempt failed = attempt(taskId, TaskPhase.FAILED, 3, UPDATED);

        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.FAILED, 3,
                failed, CREATED, UPDATED, "actor", "source", null, null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.FAILED, 3,
                failed, CREATED, UPDATED, "actor", "source", "CODE", "", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Task(taskId, TaskPhase.RUNNING, 3,
                attempt(taskId, TaskPhase.RUNNING, 3, null), CREATED, UPDATED,
                "actor", "source", "CODE", "message", Set.of()));

        Task cancelled = new Task(taskId, TaskPhase.CANCELLED, 3,
                attempt(taskId, TaskPhase.CANCELLED, 3, UPDATED), CREATED, UPDATED,
                "actor", "source", null, null, Set.of());
        assertEquals(TaskPhase.CANCELLED, cancelled.attempt().phase());
    }

    @Test
    void requiresTerminalAttemptCompletionAndNonterminalAttemptWithoutCompletion() {
        UUID taskId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> attempt(taskId, TaskPhase.FAILED, 1, null));
        assertThrows(IllegalArgumentException.class, () -> attempt(taskId, TaskPhase.RUNNING, 1, UPDATED));
        assertDoesNotThrow(() -> attempt(taskId, TaskPhase.FAILED, 1, UPDATED));
    }

    @Test
    void requiresLeaseExpiryAtOrAfterAttemptCreationButAllowsLaterExpiry() {
        UUID taskId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new TaskAttempt(UUID.randomUUID(), taskId,
                UUID.randomUUID(), TaskPhase.ASSIGNED, CREATED, CREATED, CREATED.minusSeconds(1),
                null, "actor", "source", null, null, 1));
        assertDoesNotThrow(() -> new TaskAttempt(UUID.randomUUID(), taskId,
                UUID.randomUUID(), TaskPhase.ASSIGNED, CREATED, UPDATED, CREATED,
                null, "actor", "source", null, null, 1));
    }

    @Test
    void processedEventIdsAreImmutableAndCopiedAtConstruction() {
        UUID eventId = UUID.randomUUID();
        Set<UUID> source = new java.util.HashSet<>();
        source.add(eventId);
        Task task = new Task(UUID.randomUUID(), TaskPhase.DRAFT, 0, null, CREATED, CREATED,
                "actor", "source", null, null, source);
        source.add(UUID.randomUUID());

        assertEquals(Set.of(eventId), task.processedEventIds());
        assertThrows(UnsupportedOperationException.class, () -> task.processedEventIds().add(UUID.randomUUID()));
    }

    private TaskAttempt attempt(UUID taskId, TaskPhase phase, long version, Instant completedAt) {
        return new TaskAttempt(UUID.randomUUID(), taskId, UUID.randomUUID(), phase, CREATED, UPDATED,
                EXPIRY, completedAt, "actor", "source",
                phase == TaskPhase.FAILED ? "CODE" : null,
                phase == TaskPhase.FAILED ? "message" : null, version);
    }
}
