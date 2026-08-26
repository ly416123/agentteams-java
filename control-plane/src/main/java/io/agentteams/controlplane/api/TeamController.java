package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.service.TeamService;
import io.agentteams.controlplane.team.TeamDeployment;
import io.agentteams.controlplane.team.TeamDeploymentService;
import io.agentteams.controlplane.team.TeamRevision;
import io.agentteams.controlplane.team.TeamRevisionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/teams")
public final class TeamController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final TeamService service;
    private final TeamRevisionService revisions;
    private final TeamDeploymentService deployments;

    public TeamController(TeamService service) {
        this.service = service;
        this.revisions = null;
        this.deployments = null;
    }

    @Autowired
    public TeamController(TeamService service, TeamRevisionService revisions, TeamDeploymentService deployments) {
        this.service = service;
        this.revisions = revisions;
        this.deployments = deployments;
    }

    @GetMapping
    public List<TeamResponse> list() {
        return service.list().stream().map(TeamResponse::from).toList();
    }

    @GetMapping("/{teamId}")
    public TeamResponse get(@PathVariable UUID teamId) {
        return TeamResponse.from(service.get(teamId));
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateTeamRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        requireIdempotencyKey(idempotencyKey);
        TeamPolicyRecord policy = new TeamPolicyRecord(UUID.randomUUID(), positive(request.maxConcurrentTasks()),
                request.requireHumanApproval(), values(request.allowedRuntimes()), values(request.requiredCapabilities()),
                Instant.now(), 0);
        TeamRecord team = service.create(idempotencyKey, required(request.name(), "name"),
                required(request.displayName(), "displayName"), policy, Instant.now());
        return ResponseEntity.status(201).body(TeamResponse.from(team));
    }

    @GetMapping("/{teamId}/members")
    public List<MemberResponse> members(@PathVariable UUID teamId) {
        return service.members(teamId).stream().map(MemberResponse::from).toList();
    }

    @GetMapping("/{teamId}/policy")
    public PolicyResponse policy(@PathVariable UUID teamId) {
        return PolicyResponse.from(service.policy(teamId));
    }

    @PutMapping("/{teamId}/policy")
    public PolicyResponse updatePolicy(@PathVariable UUID teamId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody PolicyRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        return PolicyResponse.from(service.updatePolicy(teamId, positive(request.maxConcurrentTasks()),
                request.requireHumanApproval(), values(request.allowedRuntimes()),
                values(request.requiredCapabilities()), request.expectedVersion(), Instant.now(), idempotencyKey));
    }

    @PostMapping("/{teamId}/members")
    public MemberResponse addMember(@PathVariable UUID teamId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody MemberRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        return MemberResponse.from(service.addMember(teamId, request.agentId(), required(request.role(), "role"),
                Instant.now(), idempotencyKey));
    }

    @DeleteMapping("/{teamId}/members/{agentId}")
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID agentId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        service.removeMember(teamId, agentId, Instant.now(), idempotencyKey);
    }

    @DeleteMapping("/{teamId}")
    public void delete(@PathVariable UUID teamId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        service.delete(teamId, Instant.now(), idempotencyKey);
    }

    @PostMapping("/{teamId}/revisions")
    public RevisionResponse createRevision(@PathVariable UUID teamId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody RevisionRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        TeamRevision revision = revisions.createDraft(teamId, request.leaderAgentId(), request.overlayJson(),
                request.memberAgentIds(), actor(request.actor()), idempotencyKey, Instant.now());
        return RevisionResponse.from(revision);
    }

    @GetMapping("/{teamId}/revisions")
    public List<RevisionResponse> revisions(@PathVariable UUID teamId) {
        return revisions.list(teamId).stream().map(RevisionResponse::from).toList();
    }

    @GetMapping("/{teamId}/revisions/{revision}")
    public RevisionResponse revision(@PathVariable UUID teamId, @PathVariable long revision) {
        return RevisionResponse.from(revisions.get(teamId, revision));
    }

    @PostMapping("/{teamId}/revisions/{revision}/review")
    public RevisionResponse review(@PathVariable UUID teamId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody VersionRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return RevisionResponse.from(revisions.review(teamId, revision, expectedVersion(request), idempotencyKey));
    }

    @PostMapping("/{teamId}/revisions/{revision}/publish")
    public RevisionResponse publish(@PathVariable UUID teamId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody VersionRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return RevisionResponse.from(revisions.publish(teamId, revision, expectedVersion(request), idempotencyKey));
    }

    @PostMapping("/{teamId}/revisions/{revision}/deployments")
    public DeploymentResponse deploy(@PathVariable UUID teamId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeploymentRequest request) {
        requireIdempotencyKey(idempotencyKey);
        TeamRevision teamRevision = revisions.get(teamId, revision);
        List<TeamDeployment.Member> members = request == null || request.members() == null ? List.of()
                : request.members().stream().map(member -> new TeamDeployment.Member(member.agentId(),
                        member.baseManifest(), member.taskOverlay())).toList();
        return DeploymentResponse.from(deployments.deploy(teamRevision, members,
                request == null ? "api" : actor(request.actor()), idempotencyKey));
    }

    @GetMapping("/{teamId}/deployments/{deploymentId}")
    public DeploymentResponse deployment(@PathVariable UUID teamId, @PathVariable UUID deploymentId) {
        return DeploymentResponse.from(deployments.find(deploymentId, teamId));
    }

    @PostMapping("/{teamId}/deployments/{deploymentId}/retry")
    public void retry(@PathVariable UUID teamId, @PathVariable UUID deploymentId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        deployments.retry(deploymentId, teamId, idempotencyKey);
    }

    @PostMapping("/{teamId}/rollback")
    public RevisionResponse rollback(@PathVariable UUID teamId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody RollbackRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        return RevisionResponse.from(revisions.rollback(teamId, request.targetRevision(), actor(request.actor()),
                idempotencyKey, Instant.now()));
    }

    public record CreateTeamRequest(String name, String displayName, Integer maxConcurrentTasks,
            boolean requireHumanApproval, List<String> allowedRuntimes, List<String> requiredCapabilities) {
    }

    public record MemberRequest(UUID agentId, String role) {
    }

    public record PolicyRequest(Integer maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities, long expectedVersion) {
    }

    public record RevisionRequest(UUID leaderAgentId, String overlayJson, List<UUID> memberAgentIds, String actor) {
    }

    public record VersionRequest(long expectedVersion) {
    }

    public record DeploymentRequest(List<MemberDeploymentRequest> members, String actor) {
    }

    public record MemberDeploymentRequest(UUID agentId, String baseManifest, String taskOverlay) {
    }

    public record RollbackRequest(long targetRevision, String actor) {
    }

    public record TeamResponse(UUID id, String name, String displayName, String status,
            Instant createdAt, Instant updatedAt, long version) {
        static TeamResponse from(TeamRecord team) {
            return new TeamResponse(team.id(), team.name(), team.displayName(), team.status(), team.createdAt(),
                    team.updatedAt(), team.version());
        }
    }

    public record MemberResponse(UUID id, UUID teamId, UUID agentId, String role, String status,
            Instant joinedAt, Instant updatedAt, long version) {
        static MemberResponse from(TeamMemberRecord member) {
            return new MemberResponse(member.id(), member.teamId(), member.agentId(), member.role(), member.status(),
                    member.joinedAt(), member.updatedAt(), member.version());
        }
    }

    public record PolicyResponse(UUID teamId, int maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities, Instant updatedAt, long version) {
        static PolicyResponse from(TeamPolicyRecord policy) {
            return new PolicyResponse(policy.teamId(), policy.maxConcurrentTasks(), policy.requireHumanApproval(),
                    policy.allowedRuntimes(), policy.requiredCapabilities(), policy.updatedAt(), policy.version());
        }
    }

    public record RevisionResponse(UUID teamId, long revision, UUID leaderAgentId, String overlayJson,
            String digest, String status, Long rollbackOfRevision, String createdBy, Instant createdAt,
            long version, List<UUID> memberAgentIds) {
        static RevisionResponse from(TeamRevision revision) {
            return new RevisionResponse(revision.teamId(), revision.revision(), revision.leaderAgentId(),
                    revision.overlayJson(), revision.digest(), revision.status().name(), revision.rollbackOfRevision(),
                    revision.createdBy(), revision.createdAt(), revision.version(), revision.memberAgentIds());
        }
    }

    public record DeploymentResponse(UUID id, UUID teamId, long teamRevision, String status,
            List<TeamDeployment.Member> members, Instant createdAt) {
        static DeploymentResponse from(TeamDeployment deployment) {
            return new DeploymentResponse(deployment.id(), deployment.teamId(), deployment.teamRevision(),
                    deployment.status(), deployment.members(), deployment.createdAt());
        }
    }

    private static int positive(Integer value) {
        if (value == null) return 1;
        if (value < 1) throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        return value;
    }

    private static List<String> values(List<String> value) {
        return value == null ? List.of() : value.stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        if (key.length() > 255) throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
    }

    private static long expectedVersion(VersionRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        if (request.expectedVersion() < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        return request.expectedVersion();
    }

    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? "api" : actor.trim();
    }
}
