package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerReconcilerBehaviorTest {

    @Test
    void firstReconcileCreatesOwnedChildrenAndSchedulesReadinessCheck() {
        ReconcilerBehaviorTestSupport fake = new ReconcilerBehaviorTestSupport();
        Worker worker = worker();

        var control = new WorkerReconciler(fake.client()).reconcile(worker, null);

        assertThat(fake.deployment).isNotNull();
        assertThat(fake.service).isNotNull();
        assertThat(fake.deployment.getMetadata().getOwnerReferences()).singleElement()
                .satisfies(owner -> assertThat(owner.getUid()).isEqualTo("worker-uid"));
        assertThat(fake.service.getMetadata().getOwnerReferences()).singleElement()
                .satisfies(owner -> assertThat(owner.getName()).isEqualTo("worker-a"));
        assertThat(worker.getStatus().getPhase()).isEqualTo("Progressing");
        assertThat(control.isUpdateStatus()).isTrue();
        assertThat(control.getScheduleDelay()).hasValue(30_000L);
    }

    @Test
    void repeatedReconcileRepairsMutatedChildrenWithoutChangingGeneration() {
        ReconcilerBehaviorTestSupport fake = new ReconcilerBehaviorTestSupport();
        Worker worker = worker();
        new WorkerReconciler(fake.client()).reconcile(worker, null);
        long generation = worker.getMetadata().getGeneration();

        fake.deployment.getSpec().setReplicas(99);
        fake.service.getSpec().setSelector(Map.of("agentteams.io/agent-id", "tampered"));

        new WorkerReconciler(fake.client()).reconcile(worker, null);

        assertThat(fake.deploymentWrites).isEqualTo(2);
        assertThat(fake.serviceWrites).isEqualTo(2);
        assertThat(fake.deployment.getSpec().getReplicas()).isEqualTo(2);
        assertThat(fake.service.getSpec().getSelector())
                .containsEntry("agentteams.io/agent-id", "agent-a");
        assertThat(worker.getMetadata().getGeneration()).isEqualTo(generation);
    }

    private static Worker worker() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withNamespace("agentteams")
                .withGeneration(4L).withUid("worker-uid").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:dev", 2,
                Map.of("WORKER_MODE", "test"), ""));
        return worker;
    }
}
