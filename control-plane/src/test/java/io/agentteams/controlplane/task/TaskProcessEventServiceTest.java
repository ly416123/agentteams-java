package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskProcessEventServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @Test
    void appendsIdempotentlyAndReplaysOnlyAuthorizedEventsAfterCursor() {
        InMemoryEventRepository repository = new InMemoryEventRepository();
        TaskProcessEventService service = new TaskProcessEventService(repository);
        TaskProcessEvent publicEvent = event(1, TaskEventVisibility.REQUESTER, "PROGRESS");
        TaskProcessEvent internalEvent = event(2, TaskEventVisibility.INTERNAL_ONLY, "TOOL_RESULT");

        assertThat(service.append(CONTEXT, publicEvent)).isEqualTo(publicEvent);
        assertThat(service.append(CONTEXT, publicEvent)).isEqualTo(publicEvent);
        service.append(CONTEXT, internalEvent);

        assertThat(service.replay(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 100))
                .containsExactly(publicEvent);
        assertThat(service.replay(CONTEXT, TASK_ID, RUN_ID, 1,
                Set.of(TaskEventVisibility.REQUESTER, TaskEventVisibility.INTERNAL_ONLY), 100))
                .containsExactly(internalEvent);
        assertThat(repository.insertions).isEqualTo(2);
    }

    @Test
    void rejectsWrongScopeAndInvalidReadBounds() {
        TaskProcessEventService service = new TaskProcessEventService(new InMemoryEventRepository());
        ExecutionContext other = new ExecutionContext("org-2", "tenant-2", "project-2", "team-2", "user-2");

        assertThatThrownBy(() -> service.append(null, event(1, TaskEventVisibility.REQUESTER, "PROGRESS")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.replay(other, TASK_ID, RUN_ID, -1,
                Set.of(TaskEventVisibility.REQUESTER), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TaskProcessEvent event(long sequence, TaskEventVisibility visibility, String type) {
        return new TaskProcessEvent(UUID.randomUUID(), TASK_ID, RUN_ID, sequence, type, visibility,
                Instant.parse("2026-08-31T00:00:00Z").plusSeconds(sequence), "corr-1", "{\"phase\":\"run\"}", null);
    }

    private static final class InMemoryEventRepository implements TaskProcessEventRepository {
        private final List<StoredEvent> events = new ArrayList<>();
        private int insertions;

        @Override
        public boolean insert(ExecutionContext context, TaskProcessEvent event) {
            if (events.stream().anyMatch(stored -> stored.event().eventId().equals(event.eventId())
                    || (stored.event().runId().equals(event.runId()) && stored.event().sequence() == event.sequence()))) {
                return false;
            }
            events.add(new StoredEvent(context, event));
            insertions++;
            return true;
        }

        @Override
        public List<TaskProcessEvent> find(ExecutionContext context, UUID taskId, UUID runId, long after,
                Set<TaskEventVisibility> visible, int limit) {
            return events.stream().filter(stored -> stored.context().sameResourceScope(context))
                    .map(StoredEvent::event).filter(event -> event.taskId().equals(taskId) && event.runId().equals(runId))
                    .filter(event -> event.sequence() > after && visible.contains(event.visibility()))
                    .sorted(java.util.Comparator.comparingLong(TaskProcessEvent::sequence)).limit(limit).toList();
        }

        private record StoredEvent(ExecutionContext context, TaskProcessEvent event) { }
    }
}
