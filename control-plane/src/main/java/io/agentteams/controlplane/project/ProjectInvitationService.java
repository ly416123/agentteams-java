package io.agentteams.controlplane.project;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project invitation and membership lifecycle boundary. */
@Service
public class ProjectInvitationService {
    private static final Duration INVITATION_TTL = Duration.ofHours(24);
    private final ProjectInvitationRepository repository;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public ProjectInvitationService(ProjectInvitationRepository repository) {
        this(repository, Clock.systemUTC(), new SecureRandom());
    }

    ProjectInvitationService(ProjectInvitationRepository repository, Clock clock) {
        this(repository, clock, new SecureRandom());
    }

    ProjectInvitationService(ProjectInvitationRepository repository, Clock clock, SecureRandom random) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Transactional
    public InvitationResult invite(UUID projectId, String idempotencyKey, String subject, ProjectRole role) {
        Principal principal = principal();
        required(idempotencyKey, "Idempotency-Key");
        String target = required(subject, "subject");
        if (role == null) throw new IllegalArgumentException("role is required");
        ProjectRecord project = project(principal.scope().tenant(), projectId);
        ProjectMembershipRecord actor = membership(project, principal.subject());
        if (!actor.role().atLeast(ProjectRole.ADMIN)
                || (actor.role() == ProjectRole.ADMIN && role.atLeast(ProjectRole.ADMIN))) {
            throw new AuthorizationException("project membership management denied");
        }

        String requestHash = hash("INVITE\u0000" + target + "\u0000" + role.name());
        var existing = repository.findInvitationIdempotency(project.tenantId(), project.id(), idempotencyKey.trim());
        if (existing.isPresent()) {
            assertSame(existing.get().requestHash(), requestHash, idempotencyKey, "project invitation");
            ProjectInvitationRecord invitation = repository.findInvitation(existing.get().invitationId())
                    .orElseThrow(() -> new IllegalStateException("invitation idempotency record disappeared"));
            return new InvitationResult(invitation, "");
        }

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        Instant now = clock.instant();
        ProjectInvitationRecord invitation = ProjectInvitationRecord.invited(UUID.randomUUID(), project.tenantId(),
                project.id(), target, role, hash(token), now.plus(INVITATION_TTL), principal.subject(), now);
        ProjectInvitationRepository.InvitationIdempotency idempotency =
                new ProjectInvitationRepository.InvitationIdempotency(project.tenantId(), project.id(),
                        idempotencyKey.trim(), requestHash, invitation.id(), now);
        if (!repository.insertInvitationIdempotency(idempotency)) {
            ProjectInvitationRepository.InvitationIdempotency winner = repository
                    .findInvitationIdempotency(project.tenantId(), project.id(), idempotencyKey.trim())
                    .orElseThrow(() -> new IllegalStateException("invitation idempotency record disappeared"));
            assertSame(winner.requestHash(), requestHash, idempotencyKey, "project invitation");
            ProjectInvitationRecord existingInvitation = repository.findInvitation(winner.invitationId())
                    .orElseThrow(() -> new IllegalStateException("invitation idempotency target disappeared"));
            return new InvitationResult(existingInvitation, "");
        }
        repository.insertInvitation(invitation);
        return new InvitationResult(invitation, token);
    }

    @Transactional
    public ProjectMembershipRecord accept(String token) {
        return accept(null, token);
    }

    @Transactional
    public ProjectMembershipRecord accept(UUID projectId, String token) {
        Principal principal = principal();
        String clearToken = required(token, "token");
        ProjectInvitationRecord invitation = repository
                .findInvitationByTokenHash(principal.scope().tenant(), hash(clearToken))
                .orElseThrow(() -> new ResourceNotFoundException("project invitation"));
        if (projectId != null && !projectId.equals(invitation.projectId())) {
            throw new AuthorizationException("project invitation project mismatch");
        }
        if (!principal.subject().equals(invitation.subject())) {
            throw new AuthorizationException("project invitation subject mismatch");
        }
        if (invitation.status() == ProjectInvitationRecord.Status.ACCEPTED) {
            return membership(invitation.tenantId(), invitation.projectId(), invitation.subject());
        }
        Instant now = clock.instant();
        if (invitation.status() != ProjectInvitationRecord.Status.INVITED
                || !invitation.expiresAt().isAfter(now)) {
            throw new ProjectMembershipConflictException("INVITATION_EXPIRED");
        }
        if (!repository.acceptInvitation(invitation.id(), now)) {
            ProjectMembershipRecord alreadyAccepted = membership(invitation.tenantId(), invitation.projectId(),
                    invitation.subject());
            return alreadyAccepted;
        }
        ProjectMembershipRecord member = ProjectMembershipRecord.create(invitation.tenantId(), invitation.projectId(),
                invitation.subject(), invitation.role(), now);
        repository.upsertMembership(member);
        return repository.findMembership(invitation.tenantId(), invitation.projectId(), invitation.subject())
                .orElse(member);
    }

    public record InvitationResult(ProjectInvitationRecord record, String token) { }

    private ProjectRecord project(String tenantId, UUID projectId) {
        return repository.findProject(tenantId, Objects.requireNonNull(projectId, "projectId"))
                .orElseThrow(() -> new ResourceNotFoundException("project", projectId));
    }

    private ProjectMembershipRecord membership(ProjectRecord project, String subject) {
        return membership(project.tenantId(), project.id(), subject);
    }

    private ProjectMembershipRecord membership(String tenantId, UUID projectId, String subject) {
        return repository.findMembership(tenantId, projectId, subject)
                .orElseThrow(() -> new AuthorizationException("project membership denied"));
    }

    private static Principal principal() {
        return PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void assertSame(String actual, String expected, String key, String operation) {
        if (!actual.equals(expected)) {
            throw new io.agentteams.controlplane.persistence.IdempotencyConflictException(key, operation);
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
