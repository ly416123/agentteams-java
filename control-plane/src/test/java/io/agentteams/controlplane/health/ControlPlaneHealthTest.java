package io.agentteams.controlplane.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class ControlPlaneHealthTest {

    @Test
    void reportsNatsDownWhenNoConnectionProbeIsConfigured() {
        ObjectProvider<NatsConnectionProbe> probes = mock();
        when(probes.getIfAvailable()).thenReturn(null);

        Health health = new NatsHealthIndicator(probes).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason",
                "NATS connection probe is not configured");
    }

    @Test
    void reportsNatsDownWhenTheProbeCannotConnect() {
        ObjectProvider<NatsConnectionProbe> probes = mock();
        NatsConnectionProbe probe = mock();
        when(probes.getIfAvailable()).thenReturn(probe);
        when(probe.isConnected()).thenReturn(false);

        Health health = new NatsHealthIndicator(probes).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void actuatorConfigurationExposesLivenessAndReadinessWithDatabaseAndNats() throws IOException {
        String applicationYaml;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            applicationYaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(applicationYaml).contains("include: livenessState");
        assertThat(applicationYaml).contains("include: readinessState,db,nats");
        assertThat(applicationYaml).contains("probes:");
    }

    @Test
    void baselinesAnExistingLocalSchemaBeforeApplyingControlPlaneMigrations() throws IOException {
        String applicationYaml;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            applicationYaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(applicationYaml).contains("baseline-on-migrate: true");
        assertThat(applicationYaml).contains("baseline-version: 0");
    }
}
