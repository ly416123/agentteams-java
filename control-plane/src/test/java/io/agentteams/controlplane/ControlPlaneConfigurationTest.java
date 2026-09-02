package io.agentteams.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

class ControlPlaneConfigurationTest {
    @Test
    void teamSyncUsesItsOwnKubernetesClientWhenWorkerProvisionerIsAlsoEnabled() throws Exception {
        Method method = ControlPlaneConfiguration.class.getDeclaredMethod(
                "teamResourceSource", KubernetesClient.class,
                io.agentteams.controlplane.team.TeamCrdSynchronizer.class, String.class);

        Qualifier qualifier = method.getParameters()[0].getAnnotation(Qualifier.class);
        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo("teamSyncKubernetesClient");
    }
}
