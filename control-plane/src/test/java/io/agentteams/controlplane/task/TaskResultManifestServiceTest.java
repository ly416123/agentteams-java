package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskResultManifestServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @Test
    void publishesFinalManifestAndFiltersArtifactsByVisibility() {
        InMemoryManifestRepository repository = new InMemoryManifestRepository();
        TaskResultManifestService service = new TaskResultManifestService(repository);
        TaskResultManifest manifest = new TaskResultManifest(TASK_ID, RUN_ID, "SUCCEEDED", "done", List.of(
                artifact("final.txt", "FINAL", TaskEventVisibility.REQUESTER),
                artifact("debug.json", "INTERMEDIATE", TaskEventVisibility.INTERNAL_ONLY)));

        assertThat(service.publish(CONTEXT, manifest)).isEqualTo(manifest);
        assertThat(service.get(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER)).orElseThrow().artifacts())
                .extracting(TaskResultManifest.ArtifactMetadata::name).containsExactly("final.txt");
    }

    @Test
    void rejectsNonTerminalStatusAndCrossTenantRead() {
        TaskResultManifestService service = new TaskResultManifestService(new InMemoryManifestRepository());
        TaskResultManifest running = new TaskResultManifest(TASK_ID, RUN_ID, "RUNNING", "not final", List.of());
        ExecutionContext other = new ExecutionContext("org-2", "tenant-2", "project-2", "team-2", "user-2");

        assertThatThrownBy(() -> service.publish(CONTEXT, running)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(other, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).isEmpty();
    }

    private static TaskResultManifest.ArtifactMetadata artifact(String name, String stage,
            TaskEventVisibility visibility) {
        return new TaskResultManifest.ArtifactMetadata(name, "s3://bucket/" + name, "text/plain", 4,
                "a".repeat(64), 1, stage, visibility);
    }

    private static final class InMemoryManifestRepository implements TaskResultManifestRepository {
        private ExecutionContext context;
        private TaskResultManifest manifest;

        @Override
        public void upsert(ExecutionContext context, TaskResultManifest manifest) {
            this.context = context;
            this.manifest = manifest;
        }

        @Override
        public java.util.Optional<TaskResultManifest> find(ExecutionContext context, UUID taskId, UUID runId,
                Set<TaskEventVisibility> visible) {
            if (this.manifest == null || !this.context.sameResourceScope(context)
                    || !this.manifest.taskId().equals(taskId) || !this.manifest.runId().equals(runId)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new TaskResultManifest(taskId, runId, manifest.status(), manifest.summary(),
                    manifest.artifacts().stream().filter(artifact -> visible.contains(artifact.visibility())).toList()));
        }
    }
}
