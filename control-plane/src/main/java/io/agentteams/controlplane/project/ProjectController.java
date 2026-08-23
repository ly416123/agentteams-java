package io.agentteams.controlplane.project;

import java.util.UUID;
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

    public ProjectController(ProjectAuthorizationService service) {
        this.service = service;
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

    @GetMapping("/{projectId}/role")
    public RoleResponse checkRole(@PathVariable UUID projectId,
            @RequestParam(value = "requiredRole", required = false) ProjectRole requiredRole) {
        return RoleResponse.from(service.checkRole(projectId, requiredRole));
    }

    public record CreateProjectRequest(String name) { }

    public record AddMemberRequest(String subject, ProjectRole role) { }

    public record ProjectResponse(UUID id, String tenantId, String name, String status, String createdBy) {
        static ProjectResponse from(ProjectRecord project) {
            return new ProjectResponse(project.id(), project.tenantId(), project.name(), project.status(),
                    project.createdBy());
        }
    }

    public record MemberResponse(UUID projectId, String subject, ProjectRole role) {
        static MemberResponse from(ProjectMembershipRecord member) {
            return new MemberResponse(member.projectId(), member.subject(), member.role());
        }
    }

    public record RoleResponse(UUID projectId, String subject, ProjectRole role, boolean allowed) {
        static RoleResponse from(ProjectAuthorizationService.RoleCheck check) {
            return new RoleResponse(check.projectId(), check.subject(), check.role(), check.allowed());
        }
    }
}
