package io.agentteams.controlplane.skill;

import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SkillCapabilityPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pins a published, immutable Skill version to an organization resource scope. */
@Service
public class SkillBindingService {
    private final SkillRepository skills;
    private final SkillBindingRepository bindings;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SkillBindingService(SkillRepository skills, SkillBindingRepository bindings) {
        this(skills, bindings, Clock.systemUTC());
    }

    SkillBindingService(SkillRepository skills, SkillBindingRepository bindings, Clock clock) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public SkillBindingRecord bind(ExecutionContext context, String projectId, String teamId, UUID skillId,
            UUID skillVersionId, String digest, String actor) {
        Objects.requireNonNull(context, "context");
        if (!context.projectId().equals(projectId) || !context.teamId().equals(teamId)) {
            throw new IllegalArgumentException("binding scope must match the execution context");
        }
        SkillVersionRecord version = skills.findVersionById(Objects.requireNonNull(skillVersionId, "skillVersionId"))
                .orElseThrow(() -> new IllegalArgumentException("skill version was not found"));
        if (!Objects.requireNonNull(skillId, "skillId").equals(version.skillId())) {
            throw new IllegalArgumentException("skill version does not belong to skill");
        }
        if (!"PUBLISHED".equals(version.lifecycle())) {
            throw new IllegalArgumentException("only a published skill version can be bound");
        }
        if (!version.digest().equals(Objects.requireNonNull(digest, "digest"))) {
            throw new IllegalArgumentException("skill version digest does not match");
        }
        SkillBindingRecord record = new SkillBindingRecord(UUID.randomUUID(), context.organizationId(),
                context.tenantId(), projectId, teamId, skillId, skillVersionId, digest,
                Instant.now(clock), required(actor, "actor"));
        return bindings.bind(record);
    }

    public SkillBindingRecord bind(ExecutionContext context, String projectId, String teamId, UUID skillId,
            UUID skillVersionId, String digest, SkillCapabilityPolicy capability, SandboxPolicy effectivePolicy,
            String actor) {
        Objects.requireNonNull(capability, "capability").requireAllowedBy(effectivePolicy);
        return bind(context, projectId, teamId, skillId, skillVersionId, digest, actor);
    }

    /** Re-evaluates the immutable manifest declaration at the runtime binding boundary. */
    public SkillBindingRecord bind(ExecutionContext context, String projectId, String teamId, UUID skillId,
            UUID skillVersionId, String digest, String manifestJson, SandboxPolicy effectivePolicy,
            String actor) {
        SkillCapabilityPolicy capability = new SkillCapabilityPolicyParser().parse(manifestJson);
        return bind(context, projectId, teamId, skillId, skillVersionId, digest, capability, effectivePolicy, actor);
    }

    public List<SkillBindingRecord> list(ExecutionContext context, String projectId, String teamId) {
        Objects.requireNonNull(context, "context");
        if (!context.projectId().equals(projectId) || !context.teamId().equals(teamId)) {
            throw new IllegalArgumentException("binding scope must match the execution context");
        }
        return List.copyOf(bindings.find(context.organizationId(), context.tenantId(), projectId, teamId));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
