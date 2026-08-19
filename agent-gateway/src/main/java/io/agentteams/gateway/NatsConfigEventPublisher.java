package io.agentteams.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ConfigAppliedEnvelope;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.PlatformEventSubjects;
import io.nats.client.JetStream;
import java.util.Objects;

/** Publishes configuration acknowledgements back to the Control Plane event stream. */
public final class NatsConfigEventPublisher implements ConfigEventPort {
    private final JetStream jetStream;
    private final ObjectMapper mapper;

    public NatsConfigEventPublisher(JetStream jetStream, ObjectMapper mapper) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void applied(ConfigAppliedCommand command) {
        try {
            ConfigAppliedEnvelope envelope = ConfigAppliedEnvelope.from(command);
            jetStream.publish(PlatformEventSubjects.agentExecution(command.agentId().toString()),
                    mapper.writeValueAsBytes(envelope));
        } catch (Exception error) {
            throw new IllegalStateException("failed to publish ConfigApplied event", error);
        }
    }
}
