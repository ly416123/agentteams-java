package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NatsGatewayPropertiesTest {

    @Test
    void scopesDurablesPerGatewayReplicaForEventFanout() {
        NatsGatewayProperties properties = new NatsGatewayProperties();
        properties.setInstanceId("gateway/pod-a");

        assertThat(properties.taskConsumerDurable()).isEqualTo("agent-gateway-gateway_pod-a");
        assertThat(properties.configConsumerDurable()).isEqualTo("agent-gateway-config-gateway_pod-a");
    }
}
