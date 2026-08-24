package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.service.TeamService;
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

@RestController
@RequestMapping("/api/v1/teams")
public final class TeamController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
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
    public PolicyResponse updatePolicy(@PathVariable UUID teamId, @RequestBody PolicyRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return PolicyResponse.from(service.updatePolicy(teamId, positive(request.maxConcurrentTasks()),
                request.requireHumanApproval(), values(request.allowedRuntimes()),
                values(request.requiredCapabilities()), request.expectedVersion(), Instant.now()));
    }

    @PostMapping("/{teamId}/members")
    public MemberResponse addMember(@PathVariable UUID teamId, @RequestBody MemberRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return MemberResponse.from(service.addMember(teamId, request.agentId(), required(request.role(), "role"),
                Instant.now()));
    }

    @DeleteMapping("/{teamId}/members/{agentId}")
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID agentId) {
        service.removeMember(teamId, agentId, Instant.now());
    }

    @DeleteMapping("/{teamId}")
    public void delete(@PathVariable UUID teamId) {
        service.delete(teamId, Instant.now());
    }

    public record CreateTeamRequest(String name, String displayName, Integer maxConcurrentTasks,
            boolean requireHumanApproval, List<String> allowedRuntimes, List<String> requiredCapabilities) {
    }

    public record MemberRequest(UUID agentId, String role) {
    }

    public record PolicyRequest(Integer maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities, long expectedVersion) {
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
}
