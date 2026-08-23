package io.agentteams.controlplane.project;

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

    @Autowired
    public ProjectAuthorizationService(ProjectRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ProjectAuthorizationService(ProjectRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
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

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
