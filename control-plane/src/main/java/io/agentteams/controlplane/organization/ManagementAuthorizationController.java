package io.agentteams.controlplane.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/management/authorizations")
public final class ManagementAuthorizationController {
    private final ManagementAuthorizationService authorization;

    public ManagementAuthorizationController(ManagementAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/assignments")
    public List<ManagementRoleRepository.ManagementRoleAssignment> assignments(
            @RequestParam String scopeType,
            @RequestParam(value = "organizationId", required = false) UUID organizationId) {
        return authorization.assignments(scopeType, organizationId);
    }

    @GetMapping("/me")
    public List<ManagementRoleRepository.ManagementRoleAssignment> currentAssignments() {
        return authorization.currentAssignments();
    }

    @GetMapping("/permissions")
    public ManagementAuthorizationService.EffectivePermissions effectivePermissions(
            @RequestParam(value = "projectId", required = false) String projectId) {
        return authorization.effectivePermissions(projectId);
    }

    @PostMapping("/organizations/{organizationId}/admins")
    public ResponseEntity<ManagementRoleRepository.ManagementRoleAssignment> grantOrganizationAdmin(
            @PathVariable UUID organizationId, @RequestBody GrantRequest request) {
        return ResponseEntity.status(201).body(authorization.grantOrganizationAdmin(organizationId, request.subject()));
    }

    @PostMapping("/platform/admins")
    public ResponseEntity<ManagementRoleRepository.ManagementRoleAssignment> grantPlatformAdmin(
            @RequestBody GrantRequest request) {
        return ResponseEntity.status(201).body(authorization.grantPlatformAdmin(request.subject()));
    }

    @PostMapping("/organizations/{organizationId}/admins/{subject}/revoke")
    public ResponseEntity<Void> revokeOrganizationAdmin(@PathVariable UUID organizationId,
            @PathVariable String subject, @RequestBody RevokeRequest request) {
        authorization.revokeOrganizationAdmin(organizationId, subject, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/platform/admins/{subject}/revoke")
    public ResponseEntity<Void> revokePlatformAdmin(@PathVariable String subject,
            @RequestBody RevokeRequest request) {
        authorization.revokePlatformAdmin(subject, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    public record GrantRequest(String subject) { }
    public record RevokeRequest(long expectedVersion) { }
}
