package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSubjectsTest {

    @Test
    void routesAgentAndTaskEventsToAggregateSubjects() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        assertThat(EventSubjects.forAggregate("agent", agentId))
                .isEqualTo("agent.events." + agentId);
        assertThat(EventSubjects.forAggregate("task", taskId))
                .isEqualTo("task.events." + taskId);
    }

    @Test
    void routesNonAggregateEventsToControlAndDeadLettersToDedicatedSubject() {
        assertThat(EventSubjects.forAggregate("task_attempt", UUID.randomUUID()))
                .isEqualTo(EventSubjects.CONTROL_EVENTS);
        assertThat(EventSubjects.DEADLETTER_EVENTS).isEqualTo("deadletter.events");
    }
}
