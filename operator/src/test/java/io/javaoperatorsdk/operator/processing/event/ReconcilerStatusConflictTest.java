package io.javaoperatorsdk.operator.processing.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.operator.Team;
import io.agentteams.operator.TeamMember;
import io.agentteams.operator.TeamPolicy;
import io.agentteams.operator.TeamReconciler;
import io.agentteams.operator.Worker;
import io.agentteams.operator.WorkerReconciler;
import io.agentteams.operator.WorkerSpec;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServerExtension;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.processing.Controller;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(KubernetesMockServerExtension.class)
@EnableKubernetesMockClient(crud = true, https = false)
class ReconcilerStatusConflictTest {

    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void statusConflictIsReturnedAsExecutionFailureForOperatorRetry() {
        server.expectCustomResource(customResource("Team", "teams"));
        Operator operator = new Operator(client,
                configuration -> configuration.checkingCRDAndValidateLocalModel(false));
        @SuppressWarnings("unchecked")
        Controller<Team> controller = (Controller<Team>) operator.register(new TeamReconciler(client),
                configuration -> configuration.settingNamespace("agentteams"));
        server.expect().patch()
                .withPath("/apis/agentteams.io/v1alpha1/namespaces/agentteams/teams/team-a/status")
                .andReturn(409, new StatusBuilder().withCode(409).withReason("Conflict")
                        .withMessage("resource version changed").build())
                .once();

        Team team = team();
        PostExecutionControl<Team> result = new ReconciliationDispatcher<Team>(controller)
                .handleExecution(new ExecutionScope<Team>(null).setResource(team));

        assertThat(result.exceptionDuringExecution()).isTrue();
        assertThat(result.getRuntimeException())
                .hasValueSatisfying(error -> assertThat(error)
                        .isInstanceOf(KubernetesClientException.class)
                        .hasMessageContaining("409"));
        assertThat(result.getUpdatedCustomResource()).isEmpty();
    }

    @Test
    void workerStatusConflictIsReturnedAsExecutionFailureForOperatorRetry() {
        server.expectCustomResource(customResource("Worker", "workers"));
        Operator operator = new Operator(client,
                configuration -> configuration.checkingCRDAndValidateLocalModel(false));
        @SuppressWarnings("unchecked")
        Controller<Worker> controller = (Controller<Worker>) operator.register(new WorkerReconciler(client),
                configuration -> configuration.settingNamespace("agentteams"));
        server.expect().put()
                .withPath("/apis/agentteams.io/v1alpha1/namespaces/agentteams/workers/worker-a/status")
                .andReturn(409, new StatusBuilder().withCode(409).withReason("Conflict")
                        .withMessage("resource version changed").build())
                .once();

        PostExecutionControl<Worker> result = new ReconciliationDispatcher<Worker>(controller)
                .handleExecution(new ExecutionScope<Worker>(null).setResource(worker()));

        assertThat(result.exceptionDuringExecution()).isTrue();
        assertThat(result.getRuntimeException())
                .hasValueSatisfying(error -> assertThat(error)
                        .isInstanceOf(KubernetesClientException.class)
                        .hasMessageContaining("409"));
        assertThat(result.getUpdatedCustomResource()).isEmpty();
    }

    private static CustomResourceDefinitionContext customResource(String kind, String plural) {
        return new CustomResourceDefinitionContext.Builder()
                .withGroup("agentteams.io")
                .withVersion("v1alpha1")
                .withKind(kind)
                .withPlural(plural)
                .withScope("Namespaced")
                .withStatusSubresource(true)
                .build();
    }

    private static Team team() {
        Team team = new Team();
        team.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("team-a")
                .withNamespace("agentteams").withGeneration(3L).withUid("team-uid")
                .withResourceVersion("7").build());
        team.setSpec(new io.agentteams.operator.TeamSpec("leader-a",
                List.of(new TeamMember("agent-a", "worker", List.of("chat"))),
                new TeamPolicy(2, true), "workspace-a", "channel-a"));
        return team;
    }

    private static Worker worker() {
        Worker worker = new Worker();
        worker.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("worker-a")
                .withNamespace("agentteams").withGeneration(4L).withUid("worker-uid")
                .withResourceVersion("7").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:dev", 2,
                java.util.Map.of(), ""));
        return worker;
    }
}
