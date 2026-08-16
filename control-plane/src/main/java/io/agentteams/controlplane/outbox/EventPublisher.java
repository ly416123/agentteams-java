package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.OutboxEventRecord;

public interface EventPublisher {

    default void publish(OutboxEventRecord event) throws Exception {
        publish(event, EventSubjects.forAggregate(event.aggregateType(), event.aggregateId()));
    }

    default void publishDeadLetter(OutboxEventRecord event, String subject) throws Exception {
        publish(event, subject);
    }

    void publish(OutboxEventRecord event, String subject) throws Exception;
}
