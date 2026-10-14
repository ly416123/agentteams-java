package io.agentteams.controlplane.quality;

import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ProjectScopeResolver;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.security.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artifact-quality")
public final class ArtifactQualityController {
    private final ArtifactQualityEventRepository events;
    private final ProjectScopeResolver projectScopes;
    private final ArtifactQualityQueryContextResolver queryContexts;
    private final ResourceAuthorizationService authorization;

    public ArtifactQualityController(ArtifactQualityEventRepository events, ProjectRepository projects,
            ArtifactQualityQueryContextResolver queryContexts) {
        this.events = events;
        this.projectScopes = new ProjectScopeResolver(projects);
        this.queryContexts = queryContexts;
        this.authorization = new ResourceAuthorizationService(projects);
    }

    @GetMapping("/events")
    public EventPage list(@RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) String artifactName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "issueType") String categoryCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        var scope = projectScopes.resolve(principal, projectId == null ? null : projectId.toString());
        Principal canonical = new Principal(principal.subject(), scope.authorizationScope(), principal.permissions());
        authorization.require(ResourceAction.TASK_READ,
                ResourceRef.team(scope.tenantId(), scope.projectId(), scope.teamId()));
        ExecutionContext context = queryContexts.resolve(canonical, scope);
        ArtifactQualityEventFilter filter = new ArtifactQualityEventFilter(scope.projectId(), taskId, artifactName,
                status, categoryCode, from, to, page, pageSize);
        ArtifactQualityEventFilter.Page result = events.find(context, filter);
        return new EventPage(result.events().stream().map(EventResponse::from).toList(), result.total(),
                result.page(), result.pageSize());
    }

    public record EventPage(List<EventResponse> events, long total, int page, int pageSize) {
        public EventPage { events = List.copyOf(events); }
    }

    /** Local audit source references are deliberately omitted from the public API. */
    public record EventResponse(UUID id, UUID taskId, UUID runId, long sequence, String eventType,
            String artifactName, UUID fileId, String manifestRef, String sha256, String categoryCode,
            String checkItem, String actionSource, String status, Double score, Double scoreDelta, String summary,
            String sourceType, String confidence, Instant occurredAt) {
        static EventResponse from(ArtifactQualityEvent event) {
            return new EventResponse(event.id(), event.taskId(), event.runId(), event.sequence(), event.eventType(),
                    event.artifactName(), event.fileId(), event.manifestRef(), event.sha256(), event.categoryCode(),
                    event.checkItem(), event.actionSource(), event.status(), event.score(), event.scoreDelta(), event.summary(),
                    event.sourceType(), event.confidence(), event.occurredAt());
        }
    }
}
