package io.agentteams.controlplane.project;

import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public final class ProjectController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final ProjectAuthorizationService service;
    private final ProjectInvitationService invitations;

    public ProjectController(ProjectAuthorizationService service, ProjectInvitationService invitations) {
        this.service = service;
        this.invitations = invitations;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateProjectRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return ResponseEntity.status(201).body(ProjectResponse.from(service.createProject(idempotencyKey, request.name())));
    }

    @PostMapping("/{projectId}/members")
    public ResponseEntity<MemberResponse> addMember(@PathVariable UUID projectId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody AddMemberRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return ResponseEntity.status(201).body(MemberResponse.from(
                service.addMember(projectId, idempotencyKey, request.subject(), request.role())));
    }

    @PostMapping("/{projectId}/invitations")
    public ResponseEntity<InvitationResponse> invite(@PathVariable UUID projectId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody InviteRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        ProjectInvitationService.InvitationResult result = invitations.invite(projectId, idempotencyKey,
                request.subject(), request.role());
        ProjectInvitationRecord invitation = result.record();
        return ResponseEntity.status(201).body(new InvitationResponse(invitation.id(), invitation.projectId(),
                invitation.subject(), invitation.role(), invitation.expiresAt(), result.token()));
    }

    @PostMapping("/{projectId}/invitations/accept")
    public MemberResponse acceptInvitation(@PathVariable UUID projectId, @RequestBody AcceptInvitationRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return MemberResponse.from(invitations.accept(projectId, request.token()));
    }

    @PostMapping("/{projectId}/owner/transfer")
    public ResponseEntity<Void> transferOwner(@PathVariable UUID projectId,
            @RequestBody TransferOwnerRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        service.transferOwner(projectId, request.newOwner(), request.expectedProjectVersion());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/members")
    public List<MemberResponse> members(@PathVariable UUID projectId) {
        return service.listMembers(projectId).stream().map(MemberResponse::from).toList();
    }

    @DeleteMapping("/{projectId}/members/{subject}")
    public ResponseEntity<Void> disableMember(@PathVariable UUID projectId, @PathVariable String subject) {
        service.disableMember(projectId, subject);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/role")
    public RoleResponse checkRole(@PathVariable UUID projectId,
            @RequestParam(value = "requiredRole", required = false) ProjectRole requiredRole) {
        return RoleResponse.from(service.checkRole(projectId, requiredRole));
    }

    public record CreateProjectRequest(String name) { }

    public record AddMemberRequest(String subject, ProjectRole role) { }

    public record InviteRequest(String subject, ProjectRole role) { }

    public record AcceptInvitationRequest(String token) { }

    public record TransferOwnerRequest(String newOwner, long expectedProjectVersion) { }

    public record ProjectResponse(UUID id, String tenantId, String name, String status, String createdBy) {
        static ProjectResponse from(ProjectRecord project) {
            return new ProjectResponse(project.id(), project.tenantId(), project.name(), project.status(),
                    project.createdBy());
        }
    }

    public record MemberResponse(UUID projectId, String subject, ProjectRole role, String status) {
        static MemberResponse from(ProjectMembershipRecord member) {
            return new MemberResponse(member.projectId(), member.subject(), member.role(), member.status());
        }
    }

    public record InvitationResponse(UUID id, UUID projectId, String subject, ProjectRole role,
            java.time.Instant expiresAt, String token) { }

    public record RoleResponse(UUID projectId, String subject, ProjectRole role, boolean allowed) {
        static RoleResponse from(ProjectAuthorizationService.RoleCheck check) {
            return new RoleResponse(check.projectId(), check.subject(), check.role(), check.allowed());
        }
    }
}
