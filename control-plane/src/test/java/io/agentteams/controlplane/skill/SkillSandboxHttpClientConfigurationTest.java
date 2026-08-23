package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SkillSandboxHttpClientConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SkillSandboxHttpClientConfiguration.class);

    @Test
    void isDisabledByDefaultAndCreatesNoClient() {
        context.run(application -> assertThat(application)
                .doesNotHaveBean(SkillSandboxScannerClient.class));
    }

    @Test
    void createsClientOnlyWhenBothExplicitGatesAreEnabled() {
        context.withPropertyValues(
                "agentteams.skill.security-scanner.external.enabled=true",
                "agentteams.skill.security-scanner.http.enabled=true",
                "agentteams.skill.security-scanner.http.endpoint=http://localhost:18080/scan")
                .run(application -> assertThat(application)
                        .hasSingleBean(SkillSandboxScannerClient.class));
    }
}
