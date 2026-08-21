package io.agentteams.controlplane.team;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TeamCrdSynchronizer {
    private final FoundationPersistenceService persistence;
    private final TeamCrdParser parser;
    private final Clock clock;

    public TeamCrdSynchronizer(FoundationPersistenceService persistence, TeamCrdParser parser, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void apply(GenericKubernetesResource resource) {
        apply(parser.parse(resource));
    }

    public void apply(TeamCrdSnapshot snapshot) {
        Instant now = clock.instant();
        persistence.inTransaction(tx -> {
            tx.agents().findById(snapshot.leaderId())
                    .orElseThrow(() -> missingAgent(snapshot.leaderId()));
            for (TeamCrdSnapshot.Member member : snapshot.members()) {
                tx.agents().findById(member.agentId())
                        .orElseThrow(() -> missingAgent(member.agentId()));
            }

            TeamRecord team = new TeamRecord(snapshot.id(), snapshot.name(), snapshot.resourceName(), "ACTIVE",
                    now, now, 0);
            tx.teams().upsert(team);
            TeamCrdSnapshot.Policy sourcePolicy = snapshot.policy();
            tx.teams().upsertPolicy(new TeamPolicyRecord(snapshot.id(), sourcePolicy.maxConcurrentTasks(),
                    sourcePolicy.requireHumanApproval(), sourcePolicy.allowedRuntimes(),
                    sourcePolicy.requiredCapabilities(), now, 0));
            List<TeamMemberRecord> members = snapshot.members().stream()
                    .map(member -> new TeamMemberRecord(memberId(snapshot.id(), member.agentId()), snapshot.id(),
                            member.agentId(), member.role(), "ACTIVE", now, now, 0))
                    .toList();
            tx.teams().replaceActiveMembers(snapshot.id(), members, now);
            return null;
        });
    }

    public void delete(GenericKubernetesResource resource) {
        delete(parser.parse(resource));
    }

    public void delete(TeamCrdSnapshot snapshot) {
        persistence.inTransaction(tx -> {
            tx.teams().markDeleted(snapshot.id(), clock.instant());
            return null;
        });
    }

    private static UUID memberId(UUID teamId, UUID agentId) {
        return UUID.nameUUIDFromBytes((teamId + "/" + agentId).getBytes(StandardCharsets.UTF_8));
    }

    private static IllegalStateException missingAgent(UUID agentId) {
        return new IllegalStateException("team member agent does not exist: " + agentId);
    }
}
