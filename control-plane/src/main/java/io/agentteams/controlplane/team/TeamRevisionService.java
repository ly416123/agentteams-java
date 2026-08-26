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

    public TeamRevisionService(TeamRevisionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlayJson,
            List<UUID> memberAgentIds, String actor, String idempotencyKey, Instant now) {
        Objects.requireNonNull(memberAgentIds, "memberAgentIds");
        String overlay = canonicalObject(overlayJson);
        TeamRevision revision = new TeamRevision(teamId, repository.nextRevision(teamId), leaderAgentId,
                overlay, sha256(overlay), TeamRevisionStatus.DRAFT, null, actor, now, 0,
                memberAgentIds);
        return repository.insert(revision, requireKey(idempotencyKey));
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

    public TeamRevision review(UUID teamId, long revisionNumber, long expectedVersion) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() != TeamRevisionStatus.DRAFT || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("only the current draft can enter review");
        }
        return repository.update(new TeamRevision(current.teamId(), current.revision(), current.leaderAgentId(),
                current.overlayJson(), current.digest(), TeamRevisionStatus.REVIEWING, current.rollbackOfRevision(),
                current.createdBy(), current.createdAt(), expectedVersion, current.memberAgentIds()));
    }

    public TeamRevision publish(UUID teamId, long revisionNumber, long expectedVersion) {
        TeamRevision current = get(teamId, revisionNumber);
        if (current.status() == TeamRevisionStatus.PUBLISHED && current.version() == expectedVersion) return current;
        if ((current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING)
                || current.version() != expectedVersion) {
            throw new TeamRevisionConflictException("team revision cannot be published");
        }
        repository.deprecatePublished(teamId, revisionNumber);
        return repository.update(new TeamRevision(current.teamId(), current.revision(), current.leaderAgentId(),
                current.overlayJson(), current.digest(), TeamRevisionStatus.PUBLISHED, current.rollbackOfRevision(),
                current.createdBy(), current.createdAt(), expectedVersion, current.memberAgentIds()));
    }

    public TeamRevision rollback(UUID teamId, long targetRevision, String actor,
            String idempotencyKey, Instant now) {
        TeamRevision target = get(teamId, targetRevision);
        return createDraft(teamId, target.leaderAgentId(), target.overlayJson(), target.memberAgentIds(), actor,
                requireKey(idempotencyKey), now, target.revision());
    }

    public TeamRevision get(UUID teamId, long revision) {
        return repository.find(teamId, revision)
                .orElseThrow(() -> new TeamRevisionConflictException("team revision does not exist"));
    }

    public List<TeamRevision> list(UUID teamId) {
        return repository.findAll(teamId);
    }

    private TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlayJson,
            List<UUID> memberAgentIds, String actor, String idempotencyKey, Instant now, long rollbackOf) {
        String overlay = canonicalObject(overlayJson);
        TeamRevision revision = new TeamRevision(teamId, repository.nextRevision(teamId), leaderAgentId, overlay,
                sha256(overlay), TeamRevisionStatus.DRAFT, rollbackOf, actor, now, 0, memberAgentIds);
        return repository.insert(revision, idempotencyKey);
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
