package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public final class TaskSourceEvidenceController {
    private final TaskSourceEvidenceService service;
    private final ExecutionContextResolver contexts;
    public TaskSourceEvidenceController(TaskSourceEvidenceService service, ExecutionContextResolver contexts) {
        this.service=service; this.contexts=contexts;
    }
    @PostMapping("/{taskId}/source-evidence")
    public TaskSourceEvidenceService.Receipt capture(@PathVariable UUID taskId,
            @RequestHeader(name="Idempotency-Key") String idempotencyKey, @RequestBody CaptureRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        if (servletRequest.getContentLengthLong() > 64 * 1024) {
            throw new IllegalArgumentException("source evidence request is too large");
        }
        return service.capture(taskId, request.attemptId(), request.leaseId(), request.callId(), request.provider(),
                idempotencyKey, request.sources(), PrincipalContext.current().map(contexts::resolve).orElse(null));
    }
    @GetMapping("/{taskId}/runs/{runId}/source-evidence")
    public TaskSourceEvidence read(@PathVariable UUID taskId,@PathVariable UUID runId) {
        var principal=PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
        return service.find(contexts.resolve(principal),taskId,runId);
    }
    public record CaptureRequest(UUID attemptId,UUID leaseId,String callId,String provider,
            List<TaskSourceEvidenceService.IncomingSource> sources) { }
}
