package io.agentteams.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ExecutionEventEnvelope;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.PlatformEventSubjects;
import io.nats.client.JetStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Publishes Agent execution events without coupling the Gateway to Control Plane internals. */
public final class NatsExecutionEventPublisher implements ExecutionEventPort {
    private final JetStream jetStream;
    private final ObjectMapper mapper;

    public NatsExecutionEventPublisher(JetStream jetStream, ObjectMapper mapper) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void apply(UUID taskId, TaskExecutionCommand command, List<ArtifactReference> artifacts) {
        publish(taskId, command.agentId(), ExecutionEventEnvelope.task(taskId, command, artifacts));
    }

    @Override
    public void renewLease(UUID taskId, LeaseRenewalCommand command) {
        publish(taskId, command.agentId(), ExecutionEventEnvelope.leaseRenewal(taskId, command));
    }

    private void publish(UUID taskId, String agentId, ExecutionEventEnvelope envelope) {
        try {
            jetStream.publish(PlatformEventSubjects.agentExecution(agentId), mapper.writeValueAsBytes(envelope));
        } catch (Exception error) {
            throw new IllegalStateException("failed to publish Agent execution event for task " + taskId, error);
        }
    }
}
