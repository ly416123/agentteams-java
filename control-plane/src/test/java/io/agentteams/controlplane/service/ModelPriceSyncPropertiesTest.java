package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPriceSyncPropertiesTest {
    @Test
    void remainsDisabledAndDoesNotRequireAnEndpointByDefault() {
        ModelPriceSyncProperties properties = new ModelPriceSyncProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void enabledSyncRequiresExplicitTargetsAndSafeEndpoint() {
        ModelPriceSyncProperties properties = new ModelPriceSyncProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("https://prices.example.test/catalog.json"));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets");

        properties.setTargets(List.of(new ModelPriceSyncProperties.Target("tenant-a", "project-a")));
        properties.validate();
    }
}
