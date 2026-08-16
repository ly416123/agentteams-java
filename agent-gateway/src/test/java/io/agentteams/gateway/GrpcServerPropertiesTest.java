package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GrpcServerPropertiesTest {

    @Test
    void usesSafeDefaultsForStandaloneGateway() {
        GrpcServerProperties properties = new GrpcServerProperties();

        assertThat(properties.getPort()).isEqualTo(9090);
        assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
