package io.agentteams.controlplane.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.config.ConfigManifestCanonicalizer;
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

    public TeamRevisionService(TeamRevisionRepository repository) {
        this(repository, repository::validatePublish);
    }

    public TeamRevisionService(TeamRevisionRepository repository, TeamRevisionPublishValidator publishValidator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publishValidator = Objects.requireNonNull(publishValidator, "publishValidator");
    }

    public TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlayJson,
            List<UUID> memberAgentIds, String actor, String idempotencyKey, Instant now) {
        Objects.requireNonNull(memberAgentIds, "memberAgentIds");
        String overlay = canonicalObject(overlayJson);
        return repository.createDraft(teamId, leaderAgentId, overlay, sha256(overlay), null, actor, now,
                memberAgentIds, requireKey(idempotencyKey));
    }

    public TeamRevision updateOverlay(UUID teamId, long revisionNumber, String overlayJson,
            long expectedVersion, String actor, Instant now) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING) {
            throw new TeamRevisionConflictException("published team revision is immutable");
        }
        if (current.version() != expectedVersion) throw new TeamRevisionConflictException("team revision version is stale");
        String overlay = canonicalObject(overlayJson);
        return repository.update(new TeamRevision(teamId, revisionNumber, current.leaderAgentId(), overlay,
                sha256(overlay), current.status(), current.rollbackOfRevision(), actor, now, expectedVersion,
                current.memberAgentIds()));
    }

    public TeamRevision review(UUID teamId, long revisionNumber, long expectedVersion, String idempotencyKey) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() == TeamRevisionStatus.REVIEWING) {
            return repository.transition(teamId, revisionNumber, expectedVersion, TeamRevisionStatus.DRAFT,
                    TeamRevisionStatus.REVIEWING, requireKey(idempotencyKey));
        }
        if (current.status() != TeamRevisionStatus.DRAFT || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("only the current draft can enter review");
        }
        return repository.transition(teamId, revisionNumber, expectedVersion, TeamRevisionStatus.DRAFT,
                TeamRevisionStatus.REVIEWING, requireKey(idempotencyKey));
    }

    public TeamRevision review(UUID teamId, long revisionNumber, long expectedVersion) {
        return review(teamId, revisionNumber, expectedVersion, "legacy-review-" + teamId + "-" + revisionNumber);
    }

    public TeamRevision publish(UUID teamId, long revisionNumber, long expectedVersion, String idempotencyKey) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() == TeamRevisionStatus.PUBLISHED) {
            if (current.version() == expectedVersion) return current;
            return repository.publish(teamId, revisionNumber, expectedVersion, requireKey(idempotencyKey));
        }
        if ((current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING)
                || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("team revision cannot be published");
        }
        publishValidator.validate(current);
        return repository.publish(teamId, revisionNumber, expectedVersion, requireKey(idempotencyKey));
    }

    public TeamRevision publish(UUID teamId, long revisionNumber, long expectedVersion) {
        return publish(teamId, revisionNumber, expectedVersion, "legacy-publish-" + teamId + "-" + revisionNumber);
    }

    public TeamRevision rollback(UUID teamId, long targetRevision, String actor,
            String idempotencyKey, Instant now) {
        TeamRevision target = get(teamId, targetRevision);
        return repository.createRollback(teamId, target, actor, now, requireKey(idempotencyKey));
    }

    public TeamRevision get(UUID teamId, long revision) {
        return repository.find(teamId, revision)
                .orElseThrow(() -> new TeamRevisionConflictException("team revision does not exist"));
    }

    public List<TeamRevision> list(UUID teamId) {
        return repository.findAll(teamId);
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

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        return key;
    }
}
