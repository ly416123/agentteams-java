package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.EndpointAddressBuilder;
import io.fabric8.kubernetes.api.model.EndpointSubsetBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSandboxStatusMapperTest {

    @Test
    void mapsAJobFailureToStableFailedStatusWithoutContainerDetails() {
        Job failed = new JobBuilder().withMetadata(currentLabels()).withStatus(new JobStatusBuilder().withFailed(1).withConditions(
                new JobConditionBuilder().withType("Failed").withReason("BackoffLimitExceeded")
                        .withMessage("token=must-not-cross-boundary").build()).build()).build();

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox(), failed, service(), null, false);

        assertThat(status.getPhase()).isEqualTo("FAILED");
        assertThat(status.getMessage()).isEqualTo("Sandbox Job failed: BackoffLimitExceeded");
        assertThat(status.getMessage()).doesNotContain("token");
    }

    @Test
    void mapsAFailedConditionEvenBeforeTheJobFailureCountIsUpdated() {
        Job failed = new JobBuilder().withMetadata(currentLabels()).withStatus(new JobStatusBuilder().withConditions(
                new JobConditionBuilder().withType("Failed").withReason("DeadlineExceeded").build()).build()).build();

        assertThat(TaskSandboxStatusMapper.map(sandbox(), failed, service(), null, false).getPhase())
                .isEqualTo("FAILED");
    }

    @Test
    void requiresEndpointAndRunnerHealthBeforeReady() {
        Job running = new JobBuilder().withMetadata(currentLabels())
                .withStatus(new JobStatusBuilder().withActive(1).build()).build();

        TaskSandboxStatus withoutEndpoint = TaskSandboxStatusMapper.map(
                sandbox(), running, service(), new EndpointsBuilder().build(), true);
        TaskSandboxStatus withUnhealthyRunner = TaskSandboxStatusMapper.map(
                sandbox(), running, service(), endpoints(), false);
        TaskSandboxStatus ready = TaskSandboxStatusMapper.map(
                sandbox(), running, service(), endpoints(), true);

        assertThat(withoutEndpoint.getPhase()).isEqualTo("PROVISIONING");
        assertThat(withUnhealthyRunner.getPhase()).isEqualTo("PROVISIONING");
        assertThat(ready.getPhase()).isEqualTo("READY");
    }

    @Test
    void marksPreviouslyReadySandboxLostWhenItsWorkloadDisappears() {
        TaskSandbox sandbox = sandbox();
        sandbox.getStatus().setPhase("READY");
        sandbox.getStatus().setObservedGeneration(3L);

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox, null, service(), null, false);

        assertThat(status.getPhase()).isEqualTo("LOST");
        assertThat(status.getObservedGeneration()).isEqualTo(3L);
    }

    @Test
    void usesTheStableTaskSandboxNameInsteadOfTheJobUidAsProviderId() {
        Job running = new JobBuilder().withMetadata(new ObjectMetaBuilder().withUid("job-uid").build())
                .withStatus(new JobStatusBuilder().withActive(1).build()).build();

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox(), running, service(), endpoints(), true);

        assertThat(status.getProviderSandboxId()).isEqualTo("task-sandbox-isolated");
    }

    @Test
    void doesNotApplyOldJobStatusToANewGeneration() {
        TaskSandbox sandbox = sandbox();
        sandbox.getMetadata().setGeneration(4L);
        sandbox.getStatus().setPhase("READY");
        sandbox.getStatus().setObservedGeneration(3L);
        Job oldJob = new JobBuilder().withMetadata(new ObjectMetaBuilder().withLabels(Map.of(
                "agentteams.io/task-sandbox-generation", "3")).build())
                .withStatus(new JobStatusBuilder().withActive(1).build()).build();

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox, oldJob, service(), endpoints(), true);

        assertThat(status.getPhase()).isEqualTo("PROVISIONING");
        assertThat(status.getObservedGeneration()).isEqualTo(3L);
    }

    @Test
    void rejectsAChildWithoutGenerationLabelInsteadOfTreatingItAsCurrent() {
        Job unlabelled = new JobBuilder().withStatus(new JobStatusBuilder().withActive(1).build()).build();

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox(), unlabelled, service(), endpoints(), true);

        assertThat(status.getPhase()).isEqualTo("PROVISIONING");
    }

    @Test
    void projectsWorkloadUidAndStableFailureCategory() {
        Job failed = new JobBuilder().withMetadata(new ObjectMetaBuilder().withUid("workload-uid")
                .withLabels(Map.of(TaskSandboxResourceFactory.GENERATION_LABEL, "3")).build())
                .withStatus(new JobStatusBuilder().withFailed(1).build()).build();

        TaskSandboxStatus status = TaskSandboxStatusMapper.map(sandbox(), failed, service(), null, false);

        assertThat(status.getWorkloadUid()).isEqualTo("workload-uid");
        assertThat(status.getFailureCategory()).isEqualTo("PROVIDER_RESPONSE_INVALID");
    }

    private static TaskSandbox sandbox() {
        TaskSandbox sandbox = new TaskSandbox();
        sandbox.setMetadata(new ObjectMetaBuilder().withName("task-sandbox-isolated")
                .withNamespace("agentteams").withGeneration(3L).withUid("sandbox-uid").build());
        sandbox.setSpec(new TaskSandboxSpec("task-1", "attempt-1", SandboxProfile.ISOLATED,
                "gvisor", "ignored", 300, Map.of("cpu", "250m")));
        return sandbox;
    }

    private static Service service() {
        return new ServiceBuilder().withSpec(new io.fabric8.kubernetes.api.model.ServiceSpecBuilder()
                .withType("ClusterIP").withClusterIP("10.0.0.1").build()).build();
    }

    private static io.fabric8.kubernetes.api.model.ObjectMeta currentLabels() {
        return new ObjectMetaBuilder().withLabels(Map.of(TaskSandboxResourceFactory.GENERATION_LABEL, "3")).build();
    }

    private static io.fabric8.kubernetes.api.model.Endpoints endpoints() {
        return new EndpointsBuilder().withSubsets(new EndpointSubsetBuilder()
                .withAddresses(new EndpointAddressBuilder().withIp("10.0.0.10").build()).build()).build();
    }
}
