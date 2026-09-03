package io.agentteams.controlplane.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.config.ConfigManifestCanonicalizer;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates immutable Team revisions and applies guarded lifecycle transitions. */
public final class TeamRevisionService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TeamRevisionRepository repository;
    private final TeamRevisionPublishValidator publishValidator;
    private final ResourceScopeRepository resourceScopes;

    public TeamRevisionService(TeamRevisionRepository repository) {
        this(repository, repository::validatePublish, null);
    }

    public TeamRevisionService(TeamRevisionRepository repository, TeamRevisionPublishValidator publishValidator) {
        this(repository, publishValidator, null);
    }

    public TeamRevisionService(TeamRevisionRepository repository, TeamRevisionPublishValidator publishValidator,
            ResourceScopeRepository resourceScopes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publishValidator = Objects.requireNonNull(publishValidator, "publishValidator");
        this.resourceScopes = resourceScopes;
    }

    public TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlayJson,
            List<UUID> memberAgentIds, String actor, String idempotencyKey, Instant now) {
        return createDraft(teamId, leaderAgentId, overlayJson, memberAgentIds, List.of(), actor, idempotencyKey, now);
    }

    public TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlayJson,
            List<UUID> memberAgentIds, List<TeamResourceBinding> resourceBindings, String actor,
            String idempotencyKey, Instant now) {
        requireTeamScope(teamId);
        Objects.requireNonNull(memberAgentIds, "memberAgentIds");
        Objects.requireNonNull(resourceBindings, "resourceBindings");
        if (memberAgentIds.isEmpty()) throw new IllegalArgumentException("members must not be empty");
        if (!memberAgentIds.contains(leaderAgentId)) {
            throw new IllegalArgumentException("leader must be included in members");
        }
        repository.validateLeaderType(teamId, leaderAgentId);
        String overlay = canonicalObject(overlayJson);
        List<TeamResourceBinding> bindings = TeamResourceBindings.canonicalize(resourceBindings);
        String digest = revisionDigest(overlay, bindings);
        if (bindings.isEmpty()) {
            return repository.createDraft(teamId, leaderAgentId, overlay, digest, null, actor, now,
                    memberAgentIds, requireKey(idempotencyKey));
        }
        return repository.createDraft(teamId, leaderAgentId, overlay, digest, null, actor, now,
                memberAgentIds, bindings, requireKey(idempotencyKey));
    }

    public TeamRevision updateOverlay(UUID teamId, long revisionNumber, String overlayJson,
            long expectedVersion, String actor, Instant now) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING) {
            throw new TeamRevisionConflictException("published team revision is immutable");
        }
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    public TeamRevision updateOverlay(UUID teamId, long revisionNumber, String overlayJson,
            long expectedVersion, String actor, Instant now, String idempotencyKey) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING) {
            throw new TeamRevisionConflictException("published team revision is immutable");
        }
        if (current.version() != expectedVersion) throw new TeamRevisionConflictException("team revision version is stale");
        String overlay = canonicalObject(overlayJson);
        String key = requireKey(idempotencyKey);
        return repository.update(new TeamRevision(teamId, revisionNumber, current.leaderAgentId(), overlay,
                revisionDigest(overlay, current.resourceBindings()), current.status(), current.rollbackOfRevision(),
                actor, now, expectedVersion, current.memberAgentIds(), current.resourceBindings()), key,
                requestHash("UPDATE", teamId, revisionNumber, expectedVersion,
                        overlay + "\u0000" + TeamResourceBindings.canonicalText(current.resourceBindings()), actor));
    }

    public TeamRevision review(UUID teamId, long revisionNumber, long expectedVersion, String idempotencyKey) {
        TeamRevision current = get(teamId, revisionNumber);
        String key = requireKey(idempotencyKey);
        String hash = requestHash("REVIEW", teamId, revisionNumber, expectedVersion, current.digest(), "");
        if (current.status() == TeamRevisionStatus.REVIEWING) {
            return repository.transition(teamId, revisionNumber, expectedVersion, TeamRevisionStatus.DRAFT,
                    TeamRevisionStatus.REVIEWING, key, hash);
        }
        if (current.status() != TeamRevisionStatus.DRAFT || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("only the current draft can enter review");
        }
        return repository.transition(teamId, revisionNumber, expectedVersion, TeamRevisionStatus.DRAFT,
                TeamRevisionStatus.REVIEWING, key, hash);
    }

    public TeamRevision review(UUID teamId, long revisionNumber, long expectedVersion) {
        return review(teamId, revisionNumber, expectedVersion, "legacy-review-" + teamId + "-" + revisionNumber);
    }

    public TeamRevision publish(UUID teamId, long revisionNumber, long expectedVersion, String idempotencyKey) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() == TeamRevisionStatus.PUBLISHED) {
            if (current.version() == expectedVersion) return current;
            return repository.publish(teamId, revisionNumber, expectedVersion, requireKey(idempotencyKey),
                    publishValidator, requestHash("PUBLISH", teamId, revisionNumber, expectedVersion,
                            current.digest(), current.createdBy()));
        }
        if ((current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING)
                || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("team revision cannot be published");
        }
        return repository.publish(teamId, revisionNumber, expectedVersion, requireKey(idempotencyKey),
                publishValidator, requestHash("PUBLISH", teamId, revisionNumber, expectedVersion,
                        current.digest(), current.createdBy()));
    }

    public TeamRevision publish(UUID teamId, long revisionNumber, long expectedVersion) {
        return publish(teamId, revisionNumber, expectedVersion, "legacy-publish-" + teamId + "-" + revisionNumber);
    }

    public TeamRevision rollback(UUID teamId, long targetRevision, String actor,
            String idempotencyKey, Instant now) {
        throw new IllegalArgumentException("expected target version is required");
    }

    public TeamRevision rollback(UUID teamId, long targetRevision, long expectedVersion, String actor,
            String idempotencyKey, Instant now) {
        TeamRevision target = get(teamId, targetRevision);
        return repository.createRollback(teamId, target, expectedVersion, actor, now, requireKey(idempotencyKey),
                publishValidator, requestHash("ROLLBACK", teamId, targetRevision, expectedVersion,
                        target.digest(), actor));
    }

    public TeamRevision get(UUID teamId, long revision) {
        requireTeamScope(teamId);
        return repository.find(teamId, revision)
                .orElseThrow(() -> new TeamRevisionConflictException("team revision does not exist"));
    }

    public List<TeamRevision> list(UUID teamId) {
        requireTeamScope(teamId);
        return repository.findAll(teamId);
    }

    private void requireTeamScope(UUID teamId) {
        if (resourceScopes == null) {
            throw new IllegalStateException("resource scope repository is required");
        }
        PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
        resourceScopes.requireVisible("TEAM", Objects.requireNonNull(teamId, "teamId"));
    }

    private static String canonicalObject(String value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("overlay must be a JSON object");
            return ConfigManifestCanonicalizer.normalize(node.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("overlay must be valid JSON object", error);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String revisionDigest(String overlay, List<TeamResourceBinding> bindings) {
        return sha256(overlay + "\u0000" + TeamResourceBindings.canonicalText(bindings));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        return key;
    }

    private static String requestHash(String operation, UUID teamId, long revision, long expectedVersion,
            String payload, String actor) {
        return sha256(operation + "\u0000" + teamId + "\u0000" + revision + "\u0000" + expectedVersion
                + "\u0000" + payload + "\u0000" + actor);
    }
}
