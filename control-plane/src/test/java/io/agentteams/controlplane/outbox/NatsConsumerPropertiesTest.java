package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NatsConsumerPropertiesTest {

    @Test
    void usesBoundedNatsConsumerDefaults() {
        NatsConsumerProperties properties = new NatsConsumerProperties();

        assertThat(properties.getConcurrency()).isEqualTo(8);
        assertThat(properties.getMaxAckPending()).isEqualTo(32);
        assertThat(properties.getDurable()).isEqualTo("control-plane-execution-events");
        properties.validate();
    }

    @Test
    void rejectsAnAckWindowSmallerThanConcurrency() {
        NatsConsumerProperties properties = new NatsConsumerProperties();
        properties.setConcurrency(16);
        properties.setMaxAckPending(8);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAckPending");
    }

    @Test
    void rejectsBlankDurable() {
        NatsConsumerProperties properties = new NatsConsumerProperties();
        properties.setDurable(" ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable");
    }
}
