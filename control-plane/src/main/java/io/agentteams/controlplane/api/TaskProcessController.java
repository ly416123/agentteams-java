package io.agentteams.controlplane.api;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProgressSnapshot;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.task.TaskProcessEventService;
import io.agentteams.controlplane.task.TaskProgressService;
import io.agentteams.controlplane.task.TaskResultManifestService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP read boundary for task execution progress, process events and result metadata. */
@RestController
@RequestMapping("/api/v1/tasks")
public final class TaskProcessController {
    private final TaskProcessEventService events;
    private final TaskProgressService progress;
    private final TaskResultManifestService results;
    private final ExecutionContextResolver contextResolver;

    public TaskProcessController(TaskProcessEventService events, TaskProgressService progress,
            TaskResultManifestService results, ExecutionContextResolver contextResolver) {
        this.events = Objects.requireNonNull(events, "events");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.results = Objects.requireNonNull(results, "results");
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
    }

    @GetMapping("/{taskId}/runs/{runId}/process-events")
    public List<TaskProcessEvent> processEvents(@PathVariable UUID taskId, @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "REQUESTER") String visibility) {
        if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative");
        TaskEventVisibility requested = visibleLevel(visibility);
        return events.replay(context(), taskId, runId, after, Set.of(requested), 100);
    }

    @GetMapping("/{taskId}/runs/{runId}/progress")
    public TaskProgressSnapshot progress(@PathVariable UUID taskId, @PathVariable UUID runId,
            @RequestParam(defaultValue = "EXECUTION") String phase) {
        return progress.snapshot(context(), taskId, runId, phase);
    }

    @GetMapping("/{taskId}/runs/{runId}/result")
    public TaskResultManifest result(@PathVariable UUID taskId, @PathVariable UUID runId,
            @RequestParam(defaultValue = "REQUESTER") String visibility) {
        TaskEventVisibility requested = visibleLevel(visibility);
        return results.get(context(), taskId, runId, Set.of(requested))
                .orElseThrow(() -> new ResourceNotFoundException("task result", runId));
    }

    private ExecutionContext context() {
        return PrincipalContext.current().map(contextResolver::resolve)
                .orElseThrow(() -> new AuthorizationException("authentication required"));
    }

    /** Internal diagnostics are never exposed through the public controller. */
    private static TaskEventVisibility visibleLevel(String value) {
        TaskEventVisibility visibility = TaskEventVisibility.from(value);
        if (visibility != TaskEventVisibility.REQUESTER) {
            throw new AuthorizationException("only requester-visible task process data is available to this API");
        }
        return visibility;
    }
}
