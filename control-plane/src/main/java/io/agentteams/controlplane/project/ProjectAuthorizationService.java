package io.agentteams.controlplane.project;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed project authorization; OIDC supplies identity and tenant context only. */
@Service
public class ProjectAuthorizationService {
    private final ProjectRepository repository;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    private static final AuditRecorder NOOP_AUDIT = event -> { };

    @Autowired
    public ProjectAuthorizationService(ProjectRepository repository, AuditRecorder auditRecorder) {
        this(repository, Clock.systemUTC(), auditRecorder);
    }

    public ProjectAuthorizationService(ProjectRepository repository) {
        this(repository, Clock.systemUTC(), NOOP_AUDIT);
    }

    ProjectAuthorizationService(ProjectRepository repository, Clock clock) {
        this(repository, clock, NOOP_AUDIT);
    }

    ProjectAuthorizationService(ProjectRepository repository, Clock clock, AuditRecorder auditRecorder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    }

    @Transactional
    public ProjectRecord createProject(String idempotencyKey, String name) {
        Principal principal = principal();
        String key = required(idempotencyKey, "Idempotency-Key");
        String projectName = required(name, "name");
        String tenantId = principal.scope().tenant();
        String requestHash = hash("CREATE_PROJECT\u0000" + projectName);

        var existing = repository.findProjectCreateIdempotency(tenantId, key);
        if (existing.isPresent()) return resolveProject(existing.get(), requestHash, key);

        Instant now = clock.instant();
        ProjectRecord project = ProjectRecord.create(UUID.randomUUID(), tenantId, projectName,
                principal.subject(), now);
        ProjectRepository.ProjectCreateIdempotency idempotency =
                new ProjectRepository.ProjectCreateIdempotency(tenantId, key, requestHash, project.id(), now);
        if (!repository.insertProjectCreateIdempotency(idempotency)) {
            return resolveProject(repository.findProjectCreateIdempotency(tenantId, key)
                    .orElseThrow(() -> new IllegalStateException("project idempotency record disappeared")),
                    requestHash, key);
        }
        repository.insertProject(project);
        repository.upsertMembership(ProjectMembershipRecord.create(tenantId, project.id(), principal.subject(),
                ProjectRole.OWNER, now));
        return project;
    }

    @Transactional
    public ProjectMembershipRecord addMember(UUID projectId, String idempotencyKey, String subject,
            ProjectRole role) {
        Principal principal = principal();
        String key = required(idempotencyKey, "Idempotency-Key");
        String memberSubject = required(subject, "subject");
        if (role == null) throw new IllegalArgumentException("role is required");
        ProjectRole memberRole = role;
        String tenantId = principal.scope().tenant();
        ProjectRecord project = project(tenantId, projectId);
        ProjectMembershipRecord actor = membership(tenantId, project.id(), principal.subject());
        if (!actor.role().atLeast(ProjectRole.ADMIN)
                || (actor.role() == ProjectRole.ADMIN && memberRole.atLeast(ProjectRole.ADMIN))) {
            throw new AuthorizationException("project membership management denied");
        }

        String requestHash = hash("ADD_MEMBER\u0000" + memberSubject + "\u0000" + memberRole.name());
        var existing = repository.findMembershipIdempotency(tenantId, project.id(), key);
        if (existing.isPresent()) {
            assertSame(existing.get().requestHash(), requestHash, key, "project membership");
            return membership(tenantId, project.id(), existing.get().subject());
        }
        Instant now = clock.instant();
        ProjectRepository.ProjectMembershipIdempotency idempotency =
                new ProjectRepository.ProjectMembershipIdempotency(tenantId, project.id(), key, requestHash,
                        memberSubject, memberRole, now);
        if (!repository.insertMembershipIdempotency(idempotency)) {
            ProjectRepository.ProjectMembershipIdempotency winner = repository
                    .findMembershipIdempotency(tenantId, project.id(), key)
                    .orElseThrow(() -> new IllegalStateException("membership idempotency record disappeared"));
            assertSame(winner.requestHash(), requestHash, key, "project membership");
            return membership(tenantId, project.id(), winner.subject());
        }
        ProjectMembershipRecord member = ProjectMembershipRecord.create(tenantId, project.id(), memberSubject,
                memberRole, now);
        repository.upsertMembership(member);
        auditMembershipChange(principal, project.id(), "PROJECT_MEMBER_ADDED", memberSubject,
                Map.of("new_role", memberRole.name(), "new_status", member.status()));
        return repository.findMembership(tenantId, project.id(), memberSubject).orElse(member);
    }

    public RoleCheck checkRole(UUID projectId, ProjectRole requiredRole) {
        Principal principal = principal();
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        ProjectMembershipRecord member = membership(principal.scope().tenant(), project.id(), principal.subject());
        return new RoleCheck(project.id(), principal.subject(), member.role(),
                requiredRole == null || member.role().atLeast(requiredRole));
    }

    public void authorize(UUID projectId, ProjectRole requiredRole) {
        RoleCheck result = checkRole(projectId, requiredRole);
        if (!result.allowed()) throw new AuthorizationException("project role denied");
    }

    public List<ProjectMembershipRecord> listMembers(UUID projectId) {
        Principal principal = principal();
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        membership(project.tenantId(), project.id(), principal.subject());
        return repository.findMemberships(project.tenantId(), project.id());
    }

    @Transactional
    public void disableMember(UUID projectId, String subject) {
        Principal principal = principal();
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        ProjectMembershipRecord actor = membership(project.tenantId(), project.id(), principal.subject());
        if (!actor.role().atLeast(ProjectRole.ADMIN)) {
            throw new AuthorizationException("project membership management denied");
        }
        String memberSubject = required(subject, "subject");
        ProjectMembershipRecord target = repository.findMembership(project.tenantId(), project.id(), memberSubject)
                .orElseThrow(() -> new ResourceNotFoundException("project member", projectId));
        if (target.role() == ProjectRole.OWNER && repository.countActiveOwners(project.tenantId(), project.id()) <= 1) {
            throw new ProjectMembershipConflictException("MEMBERSHIP_LAST_OWNER");
        }
        if (!repository.deactivateMembership(project.tenantId(), project.id(), memberSubject, clock.instant())) {
            throw new ProjectMembershipConflictException("MEMBERSHIP_VERSION_CONFLICT");
        }
        auditMembershipChange(principal, project.id(), "PROJECT_MEMBER_DISABLED", memberSubject,
                Map.of("previous_role", target.role().name(), "previous_status", target.status(),
                        "new_status", "INACTIVE"));
    }

    @Transactional
    public void transferOwner(UUID projectId, String newOwner, long expectedProjectVersion) {
        Principal principal = principal();
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        if (project.version() != expectedProjectVersion) {
            throw new ProjectMembershipConflictException("PROJECT_VERSION_CONFLICT");
        }
        ProjectMembershipRecord actor = membership(project.tenantId(), project.id(), principal.subject());
        if (actor.role() != ProjectRole.OWNER) {
            throw new AuthorizationException("owner transfer denied");
        }
        String target = required(newOwner, "newOwner");
        if (principal.subject().equals(target)) {
            throw new ProjectMembershipConflictException("OWNER_TRANSFER_TARGET_INVALID");
        }
        ProjectMembershipRecord targetMember = membership(project.tenantId(), project.id(), target);
        if (targetMember.role() == ProjectRole.OWNER) {
            throw new ProjectMembershipConflictException("OWNER_TRANSFER_TARGET_INVALID");
        }
        if (!repository.transferOwnership(project.tenantId(), project.id(), principal.subject(), target,
                expectedProjectVersion, clock.instant())) {
            throw new ProjectMembershipConflictException("PROJECT_VERSION_CONFLICT");
        }
        auditMembershipChange(principal, project.id(), "PROJECT_OWNER_TRANSFERRED", target,
                Map.of("previous_owner_hash", hash(principal.subject()), "new_role", ProjectRole.OWNER.name()));
    }

    @Transactional
    public void enableMember(UUID projectId, String subject, long expectedMembershipVersion) {
        Principal principal = principal();
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        ProjectMembershipRecord actor = membership(project.tenantId(), project.id(), principal.subject());
        if (!actor.role().atLeast(ProjectRole.ADMIN)) {
            throw new AuthorizationException("project membership management denied");
        }
        String memberSubject = required(subject, "subject");
        ProjectMembershipRecord target = repository.findMembershipIncludingInactive(project.tenantId(), project.id(),
                memberSubject).orElseThrow(() -> new ResourceNotFoundException("project member", projectId));
        if (target.status().equals("ACTIVE")) return;
        if (!repository.updateMembershipStatus(project.tenantId(), project.id(), memberSubject, "ACTIVE",
                expectedMembershipVersion, clock.instant())) {
            throw new ProjectMembershipConflictException("MEMBERSHIP_VERSION_CONFLICT");
        }
        auditMembershipChange(principal, project.id(), "PROJECT_MEMBER_ENABLED", memberSubject,
                Map.of("previous_role", target.role().name(), "previous_status", target.status(),
                        "new_role", target.role().name(), "new_status", "ACTIVE"));
    }

    @Transactional
    public void changeRole(UUID projectId, String subject, ProjectRole role, long expectedMembershipVersion) {
        Principal principal = principal();
        if (role == null) throw new IllegalArgumentException("role is required");
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        ProjectMembershipRecord actor = membership(project.tenantId(), project.id(), principal.subject());
        if (!actor.role().atLeast(ProjectRole.ADMIN)
                || (actor.role() == ProjectRole.ADMIN && role.atLeast(ProjectRole.ADMIN))) {
            throw new AuthorizationException("project membership management denied");
        }
        if (role == ProjectRole.OWNER) {
            throw new ProjectMembershipConflictException("OWNER_TRANSFER_REQUIRED");
        }
        String memberSubject = required(subject, "subject");
        ProjectMembershipRecord target = membership(project.tenantId(), project.id(), memberSubject);
        if (target.role() == ProjectRole.OWNER && repository.countActiveOwners(project.tenantId(), project.id()) <= 1) {
            throw new ProjectMembershipConflictException("MEMBERSHIP_LAST_OWNER");
        }
        if (!repository.updateMembershipRole(project.tenantId(), project.id(), memberSubject, role,
                expectedMembershipVersion, clock.instant())) {
            throw new ProjectMembershipConflictException("MEMBERSHIP_VERSION_CONFLICT");
        }
        auditMembershipChange(principal, project.id(), "PROJECT_MEMBER_ROLE_CHANGED", memberSubject,
                Map.of("previous_role", target.role().name(), "new_role", role.name(),
                        "previous_status", target.status()));
    }

    public record RoleCheck(UUID projectId, String subject, ProjectRole role, boolean allowed) { }

    private ProjectRecord project(String tenantId, UUID projectId) {
        return repository.findProject(tenantId, Objects.requireNonNull(projectId, "projectId"))
                .orElseThrow(() -> new ResourceNotFoundException("project", projectId));
    }

    private ProjectMembershipRecord membership(String tenantId, UUID projectId, String subject) {
        return repository.findMembership(tenantId, projectId, subject)
                .orElseThrow(() -> new AuthorizationException("project membership denied"));
    }

    private ProjectRecord resolveProject(ProjectRepository.ProjectCreateIdempotency existing, String hash,
            String key) {
        assertSame(existing.requestHash(), hash, key, "project");
        return project(existing.tenantId(), existing.projectId());
    }

    private static Principal principal() {
        return PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void assertSame(String actual, String expected, String key, String operation) {
        if (!actual.equals(expected)) throw new IdempotencyConflictException(key, operation);
    }

    private void auditMembershipChange(Principal actor, UUID projectId, String action, String targetSubject,
            Map<String, String> attributes) {
        try {
            Map<String, String> safeAttributes = new LinkedHashMap<>(attributes);
            safeAttributes.put("target_subject_hash", hash(targetSubject));
            auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor.subject(), action, "project_member",
                    projectId.toString(), safeAttributes, clock.instant()));
        } catch (RuntimeException ignored) {
            // Membership state is authoritative; an unavailable audit sink must not change its result.
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
