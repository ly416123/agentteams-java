package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SkillApprovalCallbackHttpClientConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SkillApprovalCallbackHttpClientConfiguration.class);

    @Test
    void isDisabledByDefaultAndLeavesSafeDefaultToSkillService() {
        context.run(application -> assertThat(application)
                .doesNotHaveBean(SkillScanApprovalPort.class)
                .doesNotHaveBean("skillApprovalCallbackHttpClient"));
    }

    @Test
    void createsCallbackOnlyWhenExplicitlyEnabledAndConfigured() {
        context.withPropertyValues(
                "agentteams.skill.approval-callback.http.enabled=true",
                "agentteams.skill.approval-callback.http.endpoint=http://localhost:18080/approval")
                .run(application -> assertThat(application)
                        .hasSingleBean(SkillScanApprovalPort.class)
                        .hasBean("skillApprovalCallbackHttpClient"));
    }

    @Test
    void enabledCallbackWithoutEndpointFailsConfiguration() {
        context.withPropertyValues("agentteams.skill.approval-callback.http.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }
}
