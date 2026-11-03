package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ProjectScopeResolver;
import io.agentteams.controlplane.security.ResourceAuthorizationMatrix;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.organization.OrganizationManagementRepository;
import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Database backed platform and organization management authorization. */
@Service
public final class ManagementAuthorizationService {
    private final ManagementRoleRepository roles;
    private final Clock clock;
    private final ProjectScopeResolver projectScopes;
    private final ProjectRepository projects;
    private final OrganizationManagementRepository organizations;
    private final AuditRecorder auditRecorder;

    public ManagementAuthorizationService(ManagementRoleRepository roles) {
        this(roles, null, null, null, event -> { }, Clock.systemUTC());
    }

    public ManagementAuthorizationService(ManagementRoleRepository roles, ProjectScopeResolver projectScopes,
            ProjectRepository projects, AuditRecorder auditRecorder) {
        this(roles, projectScopes, projects, null, auditRecorder, Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ManagementAuthorizationService(ManagementRoleRepository roles, ProjectScopeResolver projectScopes,
            ProjectRepository projects, OrganizationManagementRepository organizations, AuditRecorder auditRecorder) {
        this(roles, projectScopes, projects, organizations, auditRecorder, Clock.systemUTC());
    }

    ManagementAuthorizationService(ManagementRoleRepository roles, ProjectScopeResolver projectScopes,
            ProjectRepository projects, AuditRecorder auditRecorder, Clock clock) {
        this(roles, projectScopes, projects, null, auditRecorder, clock);
    }

    ManagementAuthorizationService(ManagementRoleRepository roles, ProjectScopeResolver projectScopes,
            ProjectRepository projects, OrganizationManagementRepository organizations,
            AuditRecorder auditRecorder, Clock clock) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.projectScopes = projectScopes;
        this.projects = projects;
        this.organizations = organizations;
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void requirePlatformAdmin() {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (!roles.hasRole(principal.subject(), "PLATFORM", null, "PLATFORM_ADMIN")) {
            throw new AuthorizationException("platform administrator required");
        }
    }

    public void requireOrganizationAdmin(UUID organizationId) {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (!roles.hasRole(principal.subject(), "PLATFORM", null, "PLATFORM_ADMIN")
                && !roles.hasRole(principal.subject(), "ORGANIZATION", organizationId, "ORGANIZATION_ADMIN")) {
            throw new AuthorizationException("organization administrator required");
        }
    }

    public boolean canReadTenantProjects(String tenantId) {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (roles.hasRole(principal.subject(), "PLATFORM", null, "PLATFORM_ADMIN")) return true;
        if (organizations == null) return false;
        UUID organizationId = organizations.organizationIdForTenantReference(tenantId)
                .orElseThrow(() -> new AuthorizationException("tenant not found"));
        return roles.hasRole(principal.subject(), "ORGANIZATION", organizationId, "ORGANIZATION_ADMIN");
    }

    public List<ManagementRoleRepository.ManagementRoleAssignment> assignments(String scopeType, UUID organizationId) {
        requirePlatformAdmin();
        String normalizedScope = Objects.requireNonNull(scopeType, "scopeType").trim().toUpperCase(java.util.Locale.ROOT);
        if (!("PLATFORM".equals(normalizedScope) && organizationId == null)
                && !("ORGANIZATION".equals(normalizedScope) && organizationId != null)) {
            throw new IllegalArgumentException("scope type and organization id do not match");
        }
        return roles.listByScope(normalizedScope, organizationId);
    }

    public List<ManagementRoleRepository.ManagementRoleAssignment> currentAssignments() {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        return roles.list(principal.subject());
    }

    public List<UUID> currentOrganizationIds() {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (roles.hasRole(principal.subject(), "PLATFORM", null, "PLATFORM_ADMIN")) return List.of();
        return roles.list(principal.subject()).stream()
                .filter(assignment -> "ORGANIZATION".equals(assignment.scopeType()))
                .map(ManagementRoleRepository.ManagementRoleAssignment::organizationId)
                .filter(Objects::nonNull).distinct().toList();
    }

    public boolean isPlatformAdmin() {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        return roles.hasRole(principal.subject(), "PLATFORM", null, "PLATFORM_ADMIN");
    }

    public EffectivePermissions effectivePermissions() {
        return effectivePermissions(null);
    }

    public EffectivePermissions effectivePermissions(String requestedProject) {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        List<ManagementRoleRepository.ManagementRoleAssignment> assignments = roles.list(principal.subject());
        java.util.Set<String> permissions = new java.util.LinkedHashSet<>();
        for (var assignment : assignments) {
            if ("PLATFORM".equals(assignment.scopeType())) {
                permissions.addAll(java.util.Set.of("admin:user:read", "admin:user:write", "organization:read",
                        "organization:write", "integration:read", "integration:write", "credential:read",
                        "credential:manage", "credential:rotate", "credential:revoke", "identity-mapping:write", "platform:organization:create", "management:role:manage",
                        "platform:organization:read", "organization:admin", "provisioning-policy:manage",
                        "user:manage", "external-user:manage", "identity-mapping:read",
                        "identity:read", "project:read", "project:create"));
            } else if ("ORGANIZATION".equals(assignment.scopeType())) {
                permissions.addAll(java.util.Set.of("organization:read", "organization:write", "integration:read",
                        "integration:write", "credential:read", "credential:manage", "credential:rotate", "credential:revoke",
                        "organization:admin", "provisioning-policy:manage", "external-user:manage",
                        "identity-mapping:read", "identity-mapping:write", "identity:read"));
            }
        }
        if (projects != null && (requestedProject == null || requestedProject.isBlank())) {
            boolean tenantAdmin = canReadTenantProjects(principal.scope().tenant());
            var visibleProjects = tenantAdmin
                    ? projects.findProjectsByTenant(principal.scope().tenant(), null, 1,
                            io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null)
                    : projects.findProjects(principal.scope().tenant(), principal.subject(), null, 1,
                            io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null);
            if (tenantAdmin || !visibleProjects.isEmpty()) permissions.add("project:read");
        }
        if (projectScopes != null && projects != null && requestedProject != null && !requestedProject.isBlank()) {
            var scope = projectScopes.resolve(principal, requestedProject);
            var member = projects.findMembership(scope.tenantId(), scope.projectId(), principal.subject())
                    .orElseThrow(() -> new AuthorizationException("project membership denied"));
            permissions.addAll(projectPermissions(member));
        }
        return new EffectivePermissions(assignments, List.copyOf(permissions));
    }

    private static java.util.Set<String> projectPermissions(ProjectMembershipRecord member) {
        var role = member.role();
        java.util.Set<String> permissions = new java.util.LinkedHashSet<>(java.util.Set.of(
                "project:read", "task:read", "team:read", "agent:read", "artifact:read", "model:read",
                "agent-spec:read", "mcp:read", "skill:read", "memory:read", "sandbox:read", "usage:read",
                "quota:read"));
        if (role.atLeast(io.agentteams.controlplane.project.ProjectRole.DEVELOPER)) {
            permissions.add("task:create");
        }
        if (role.atLeast(io.agentteams.controlplane.project.ProjectRole.OPERATOR)) {
            permissions.addAll(java.util.Set.of("task:cancel", "task:retry", "task:pause"));
        }
        if (role.atLeast(io.agentteams.controlplane.project.ProjectRole.ADMIN)) {
            permissions.addAll(java.util.Set.of("task:approve", "task:reject", "team:write", "agent:write",
                    "artifact:write", "model:write", "agent-spec:write", "mcp:write", "skill:write",
                    "agent-spec:review", "agent-spec:publish", "agent-spec:instantiate", "agent-spec:deploy",
                    "skill:review", "skill:publish", "skill:package:upload",
                    "project:member:manage", "memory:govern", "memory:export", "usage:export", "quota:write", "audit:read"));
        }
        return permissions;
    }

    public record EffectivePermissions(List<ManagementRoleRepository.ManagementRoleAssignment> assignments,
            List<String> permissions) { }

    public ManagementRoleRepository.ManagementRoleAssignment grantOrganizationAdmin(UUID organizationId,
            String subject) {
        requirePlatformAdmin();
        String actor = PrincipalContext.current().orElseThrow().subject();
        var assignment = roles.upsert(required(subject), "ORGANIZATION", Objects.requireNonNull(organizationId),
                "ORGANIZATION_ADMIN", actor, clock.instant());
        auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, "ORGANIZATION_ADMIN_GRANTED",
                "management_role", organizationId.toString(), java.util.Map.of("scope_type", "ORGANIZATION"),
                clock.instant()));
        return assignment;
    }

    public ManagementRoleRepository.ManagementRoleAssignment grantPlatformAdmin(String subject) {
        requirePlatformAdmin();
        String actor = PrincipalContext.current().orElseThrow().subject();
        var assignment = roles.upsert(required(subject), "PLATFORM", null, "PLATFORM_ADMIN", actor, clock.instant());
        auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, "PLATFORM_ADMIN_GRANTED", "management_role",
                "platform", java.util.Map.of("scope_type", "PLATFORM"), clock.instant()));
        return assignment;
    }

    public void revokeOrganizationAdmin(UUID organizationId, String subject, long expectedVersion) {
        requirePlatformAdmin();
        String actor = PrincipalContext.current().orElseThrow().subject();
        String target = required(subject);
        if (!roles.updateStatus(target, "ORGANIZATION", Objects.requireNonNull(organizationId), "DISABLED",
                expectedVersion, actor, clock.instant())) {
            throw new ManagementRoleConflictException("MANAGEMENT_ROLE_VERSION_CONFLICT");
        }
        auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, "ORGANIZATION_ADMIN_REVOKED",
                "management_role", organizationId.toString(), java.util.Map.of("target_subject_hash", hash(target)),
                clock.instant()));
    }

    public void revokePlatformAdmin(String subject, long expectedVersion) {
        requirePlatformAdmin();
        String actor = PrincipalContext.current().orElseThrow().subject();
        String target = required(subject);
        if (actor.equals(target)) {
            throw new AuthorizationException("cannot revoke the current platform administrator");
        }
        if (!roles.updateStatus(target, "PLATFORM", null, "DISABLED", expectedVersion, actor, clock.instant())) {
            throw new ManagementRoleConflictException("MANAGEMENT_ROLE_VERSION_CONFLICT");
        }
        auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, "PLATFORM_ADMIN_REVOKED", "management_role",
                "platform", java.util.Map.of("target_subject_hash", hash(target)), clock.instant()));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String required(String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject is required");
        return subject.trim();
    }
}
