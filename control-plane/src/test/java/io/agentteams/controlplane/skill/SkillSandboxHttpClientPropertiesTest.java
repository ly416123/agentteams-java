package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SkillSandboxHttpClientPropertiesTest {

    @Test
    void usesFourConcurrentScansByDefault() {
        SkillSandboxHttpClientProperties properties = validProperties();

        assertThat(properties.getMaxConcurrency()).isEqualTo(4);
        properties.validate();
    }

    @Test
    void rejectsInvalidScanConcurrency() {
        SkillSandboxHttpClientProperties properties = validProperties();
        properties.setMaxConcurrency(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrency");
    }

    private static SkillSandboxHttpClientProperties validProperties() {
        SkillSandboxHttpClientProperties properties = new SkillSandboxHttpClientProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://localhost:18080/scan"));
        return properties;
    }
}
