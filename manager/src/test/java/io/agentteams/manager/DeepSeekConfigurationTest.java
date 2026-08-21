package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeepSeekConfigurationTest {

    @Test
    void usesDeepSeekV4FlashByDefault() {
        DeepSeekConfiguration configuration = DeepSeekConfiguration.fromEnvironment(
                Map.of("DEEPSEEK_API_KEY", "local-test-key"));

        assertThat(configuration.apiKey()).isEqualTo("local-test-key");
        assertThat(configuration.model()).isEqualTo("deepseek-v4-flash");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void acceptsModelAndTimeoutOverrides() {
        DeepSeekConfiguration configuration = DeepSeekConfiguration.fromEnvironment(Map.of(
                "DEEPSEEK_API_KEY", "local-test-key",
                "DEEPSEEK_MODEL", "deepseek-v4-flash",
                "DEEPSEEK_TIMEOUT_SECONDS", "45"));

        assertThat(configuration.model()).isEqualTo("deepseek-v4-flash");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> DeepSeekConfiguration.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEEPSEEK_API_KEY must be set");
    }

    @Test
    void rejectsInvalidTimeout() {
        assertThatThrownBy(() -> DeepSeekConfiguration.fromEnvironment(Map.of(
                "DEEPSEEK_API_KEY", "local-test-key",
                "DEEPSEEK_TIMEOUT_SECONDS", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEEPSEEK_TIMEOUT_SECONDS must be a positive integer");
    }
}
