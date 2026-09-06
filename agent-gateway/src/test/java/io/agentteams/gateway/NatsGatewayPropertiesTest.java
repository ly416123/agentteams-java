package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NatsGatewayPropertiesTest {

    @Test
    void usesBoundedNatsConsumerDefaults() {
        NatsGatewayProperties properties = new NatsGatewayProperties();

        assertThat(properties.getConcurrency()).isEqualTo(8);
        assertThat(properties.getMaxAckPending()).isEqualTo(32);
        properties.validate();
    }

    @Test
    void rejectsAnAckWindowSmallerThanConcurrency() {
        NatsGatewayProperties properties = new NatsGatewayProperties();
        properties.setConcurrency(16);
        properties.setMaxAckPending(8);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAckPending");
    }

    @Test
    void scopesDurablesPerGatewayReplicaForEventFanout() {
        NatsGatewayProperties properties = new NatsGatewayProperties();
        properties.setInstanceId("gateway/pod-a");

        assertThat(properties.taskConsumerDurable()).isEqualTo("agent-gateway-gateway_pod-a");
        assertThat(properties.configConsumerDurable()).isEqualTo("agent-gateway-config-gateway_pod-a");
    }
}
