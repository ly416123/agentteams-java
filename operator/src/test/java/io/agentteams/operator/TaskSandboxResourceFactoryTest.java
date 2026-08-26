package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSandboxResourceFactoryTest {

    @Test
    void buildsARestrictedStableJobForAnIsolatedSandbox() {
        TaskSandbox sandbox = new TaskSandbox();
        sandbox.setMetadata(new ObjectMetaBuilder().withName("task-sandbox-isolated")
                .withNamespace("agentteams").withGeneration(3L).withUid("sandbox-uid").build());
        sandbox.setSpec(new TaskSandboxSpec("task-1", "attempt-1", SandboxProfile.ISOLATED,
                "gvisor", "example/sandbox:v1", 300, Map.of("cpu", "250m", "memory", "256Mi")));

        Job job = new TaskSandboxResourceFactory(Map.of(
                SandboxProfile.ISOLATED, "gvisor", SandboxProfile.HARDENED, "kata-qemu")).job(sandbox);

        assertThat(job.getMetadata().getName()).isEqualTo("task-sandbox-isolated-job");
        assertThat(job.getMetadata().getLabels())
                .containsEntry("agentteams.io/task-id", "task-1")
                .containsEntry("agentteams.io/attempt-id", "attempt-1")
                .containsEntry("agentteams.io/sandbox-profile", "ISOLATED")
                .containsEntry("agentteams.io/task-sandbox-generation", "3");
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(300L);
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(300L);
        assertThat(job.getSpec().getTemplate().getSpec().getRuntimeClassName()).isEqualTo("gvisor");
        assertThat(job.getSpec().getTemplate().getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(job.getSpec().getTemplate().getSpec().getHostNetwork()).isFalse();
        assertThat(job.getSpec().getTemplate().getSpec().getHostPID()).isFalse();
        assertThat(job.getSpec().getTemplate().getSpec().getVolumes()).isNullOrEmpty();
        assertThat(job.getSpec().getTemplate().getSpec().getContainers()).singleElement()
                .satisfies(container -> {
                    assertThat(container.getSecurityContext().getPrivileged()).isFalse();
                    assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
                    assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
                    assertThat(container.getResources().getRequests())
                            .containsKeys("cpu", "memory");
                });
    }

    @Test
    void customResourceStartsWithNonNullSpecAndStatus() {
        TaskSandbox sandbox = new TaskSandbox();

        assertThat(sandbox.getSpec()).isNotNull();
        assertThat(sandbox.getStatus()).isNotNull();
    }
}
