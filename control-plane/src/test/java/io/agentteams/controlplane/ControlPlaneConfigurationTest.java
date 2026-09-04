package io.agentteams.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.agent.AgentPresenceConsistencyJob;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyRepository;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyService;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class ControlPlaneConfigurationTest {
    private static final String PRESENCE_ROOT = "agentteams.agent-presence-consistency";

    @Test
    void teamSyncUsesItsOwnKubernetesClientWhenWorkerProvisionerIsAlsoEnabled() throws Exception {
        Method method = ControlPlaneConfiguration.class.getDeclaredMethod(
                "teamResourceSource", KubernetesClient.class,
                io.agentteams.controlplane.team.TeamCrdSynchronizer.class, String.class);

        Qualifier qualifier = method.getParameters()[0].getAnnotation(Qualifier.class);
        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo("teamSyncKubernetesClient");
    }

    /**
     * The presence reconciliation is only useful if Spring actually registers it: a mistyped flag or a
     * copied-over task-state-consistency key would silently leave the stale READY rows unrepaired.
     */
    @Test
    void agentPresenceReconciliationIsRegisteredUnderItsOwnPropertyRoot() throws Exception {
        Method repository = ControlPlaneConfiguration.class.getDeclaredMethod(
                "agentPresenceConsistencyRepository", DataSource.class);
        Method service = ControlPlaneConfiguration.class.getDeclaredMethod(
                "agentPresenceConsistencyService", AgentPresenceConsistencyRepository.class);
        Method job = ControlPlaneConfiguration.class.getDeclaredMethod("agentPresenceConsistencyJob",
                AgentPresenceConsistencyService.class, SchedulerLeaseService.class, Clock.class,
                String.class, Duration.class, Duration.class, int.class);

        String intervalKey = placeholderKey(AgentPresenceConsistencyJob.class
                .getMethod("scheduledRun").getAnnotation(Scheduled.class).fixedDelayString());
        assertThat(intervalKey).startsWith(PRESENCE_ROOT + ".");

        for (Method bean : List.of(repository, service, job)) {
            ConditionalOnProperty gate = bean.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).isNotNull();
            assertThat(gate.name()).containsExactly(PRESENCE_ROOT + ".enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
            assertThat(gate.matchIfMissing()).isTrue();
        }

        for (Parameter parameter : job.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null && value.value().contains("agentteams.")) {
                assertThat(placeholderKey(value.value())).startsWith(PRESENCE_ROOT + ".");
            }
        }
    }

    /**
     * The pending timeout shares the same registration risk: a mistyped key would silently keep
     * unacknowledged deployment members PENDING forever, which is the original L5 symptom.
     */
    @Test
    void teamDeploymentPendingTimeoutIsRegisteredUnderItsOwnPropertyRoot() throws Exception {
        String root = "agentteams.team-deployment-pending-timeout";
        Method repository = ControlPlaneConfiguration.class.getDeclaredMethod(
                "teamDeploymentPendingTimeoutRepository", DataSource.class);
        Method service = ControlPlaneConfiguration.class.getDeclaredMethod(
                "teamDeploymentPendingTimeoutService",
                io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutRepository.class);
        Method job = ControlPlaneConfiguration.class.getDeclaredMethod("teamDeploymentPendingTimeoutJob",
                io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutService.class,
                SchedulerLeaseService.class, Clock.class,
                String.class, Duration.class, Duration.class, int.class);

        String intervalKey = placeholderKey(io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutJob.class
                .getMethod("scheduledRun").getAnnotation(Scheduled.class).fixedDelayString());
        assertThat(intervalKey).startsWith(root + ".");

        for (Method bean : List.of(repository, service, job)) {
            ConditionalOnProperty gate = bean.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).isNotNull();
            assertThat(gate.name()).containsExactly(root + ".enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
            assertThat(gate.matchIfMissing()).isTrue();
        }

        for (Parameter parameter : job.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null && value.value().contains("agentteams.")) {
                assertThat(placeholderKey(value.value())).startsWith(root + ".");
            }
        }
    }

    private static String placeholderKey(String expression) {
        assertThat(expression).startsWith("${");
        String body = expression.substring(2, expression.length() - 1);
        int colon = body.indexOf(':');
        return colon < 0 ? body : body.substring(0, colon);
    }
}
