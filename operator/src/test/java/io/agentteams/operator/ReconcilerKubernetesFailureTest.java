package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServerExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(KubernetesMockServerExtension.class)
@EnableKubernetesMockClient(crud = true, https = false)
class ReconcilerKubernetesFailureTest {

    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void workerRecoversFromOneTransientServerErrorWithinTheReconcile() {
        server.expect().post().withPath("/apis/apps/v1/namespaces/agentteams/deployments")
                .andReturn(500, status(500, "internal error")).once();
        Worker worker = worker();
        WorkerReconciler reconciler = new WorkerReconciler(client);

        var control = reconciler.reconcile(worker, null);

        assertThat(control.isUpdateStatus()).isTrue();
        assertThat(worker.getStatus().getPhase()).isEqualTo("Progressing");
    }

    @Test
    void teamRecoversFromOneTransientTooManyRequestsResponseWithinTheReconcile() {
        server.expect().post().withPath("/api/v1/namespaces/agentteams/configmaps")
                .andReturn(429, status(429, "too many requests")).once();
        Team team = team();
        TeamReconciler reconciler = new TeamReconciler(client);

        var control = reconciler.reconcile(team, null);

        assertThat(control.isPatchStatus()).isTrue();
        assertThat(team.getStatus().getPhase()).isEqualTo("Applied");
    }

    @Test
    void workerPropagatesPersistentServerErrorWithoutReportingProgress() {
        server.expect().post().withPath("/apis/apps/v1/namespaces/agentteams/deployments")
                .andReturn(500, status(500, "persistent error")).always();
        Worker worker = worker();

        assertThatThrownBy(() -> new WorkerReconciler(client).reconcile(worker, null))
                .isInstanceOf(KubernetesClientException.class);
        assertThat(worker.getStatus().getPhase()).isNull();
    }

    @Test
    void workerPropagatesShortKubernetesApiOutageInsteadOfReportingProgress() {
        Worker worker = worker();
        server.destroy();

        assertThatThrownBy(() -> new WorkerReconciler(client).reconcile(worker, null))
                .isInstanceOf(KubernetesClientException.class);
        assertThat(worker.getStatus().getPhase()).isNull();
    }

    private static io.fabric8.kubernetes.api.model.Status status(int code, String message) {
        return new StatusBuilder().withCode(code).withReason(message).withMessage(message).build();
    }

    private static Worker worker() {
        Worker worker = new Worker();
        worker.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("worker-a")
                .withNamespace("agentteams").withGeneration(4L).withUid("worker-uid").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:dev", 2,
                java.util.Map.of(), ""));
        return worker;
    }

    private static Team team() {
        Team team = new Team();
        team.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("team-a")
                .withNamespace("agentteams").withGeneration(3L).withUid("team-uid").build());
        team.setSpec(new TeamSpec("leader-a", List.of(new TeamMember("agent-a", "worker", List.of("chat"))),
                new TeamPolicy(2, true), "workspace-a", "channel-a"));
        return team;
    }
}
