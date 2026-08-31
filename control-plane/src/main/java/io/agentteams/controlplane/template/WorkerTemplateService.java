package io.agentteams.controlplane.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.config.ConfigManifestCanonicalizer;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class WorkerTemplateService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WorkerTemplateRepository repository;
    private final TemplateInstanceProvisioner provisioner;
    private final ResourceScopeRepository resourceScopes;
    private final Clock clock;

    public WorkerTemplateService(WorkerTemplateRepository repository, TemplateInstanceProvisioner provisioner,
            ResourceScopeRepository resourceScopes) {
        this(repository, provisioner, resourceScopes, Clock.systemUTC());
    }

    public WorkerTemplateService(WorkerTemplateRepository repository, TemplateInstanceProvisioner provisioner,
            ResourceScopeRepository resourceScopes, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.resourceScopes = Objects.requireNonNull(resourceScopes, "resourceScopes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkerTemplate create(String idempotencyKey, CreateInput input, Instant now) {
        Principal principal = principal();
        String key = required(idempotencyKey, "Idempotency-Key");
        Objects.requireNonNull(input, "input");
        String name = required(input.name(), "name");
        String displayName = required(input.displayName(), "displayName");
        String hash = sha256(name + "\u0000" + displayName + "\u0000" + principal.scope());
        var existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) throw new TemplateConflictException("idempotency key request mismatch");
            return get(existing.get().templateId());
        }
        WorkerTemplate template = new WorkerTemplate(UUID.randomUUID(), principal.scope().tenant(),
                principal.scope().project(), name, displayName, null, 0, now, now, 0);
        if (!repository.insertIdempotency(key, hash, template.id(), now)) {
            var winner = repository.findIdempotency(key).orElseThrow();
            if (!winner.requestHash().equals(hash)) throw new TemplateConflictException("idempotency key request mismatch");
            return get(winner.templateId());
        }
        repository.insertTemplate(template);
        return template;
    }

    public WorkerTemplate get(UUID templateId) {
        WorkerTemplate template = repository.findTemplate(Objects.requireNonNull(templateId, "templateId"))
                .orElseThrow(() -> new IllegalArgumentException("worker template does not exist: " + templateId));
        requireVisible(template);
        return template;
    }

    public List<WorkerTemplate> list() {
        Principal principal = principal();
        return repository.findTemplates(principal.scope().tenant(), principal.scope().project());
    }

    public WorkerTemplateRevision createRevision(UUID templateId, String specJson, String actor, String idempotencyKey) {
        WorkerTemplate template = get(templateId);
        String canonical = canonicalObject(specJson);
        long revision = repository.nextRevision(templateId);
        return repository.createRevision(templateId, revision, canonical, sha256(canonical), required(actor, "actor"),
                clock.instant(), required(idempotencyKey, "Idempotency-Key"));
    }

    public List<WorkerTemplateRevision> revisions(UUID templateId) {
        get(templateId);
        return repository.findRevisions(templateId);
    }

    public WorkerTemplateRevision revision(UUID templateId, long revision) {
        get(templateId);
        return repository.findRevision(templateId, revision)
                .orElseThrow(() -> new TemplateConflictException("worker template revision does not exist"));
    }

    public WorkerTemplateRevision review(UUID templateId, long revision, long expectedVersion, String idempotencyKey) {
        WorkerTemplateRevision current = revision(templateId, revision);
        if (current.status() != TemplateStatus.DRAFT || current.version() != expectedVersion) {
            throw new TemplateConflictException("only the current draft can enter review");
        }
        return repository.transition(templateId, revision, expectedVersion, TemplateStatus.DRAFT,
                TemplateStatus.REVIEWING, required(idempotencyKey, "Idempotency-Key"));
    }

    public WorkerTemplateRevision publish(UUID templateId, long revision, long expectedVersion, String idempotencyKey) {
        WorkerTemplateRevision current = revision(templateId, revision);
        if (current.status() == TemplateStatus.PUBLISHED && current.version() == expectedVersion) return current;
        if ((current.status() != TemplateStatus.DRAFT && current.status() != TemplateStatus.REVIEWING)
                || current.version() != expectedVersion) {
            throw new TemplateConflictException("template revision cannot be published");
        }
        return repository.publish(templateId, revision, expectedVersion, required(idempotencyKey, "Idempotency-Key"));
    }

    public WorkerTemplateInstance instantiate(UUID templateId, long revision, String idempotencyKey) {
        WorkerTemplateRevision published = revision(templateId, revision);
        if (published.status() != TemplateStatus.PUBLISHED) {
            throw new TemplateConflictException("only a PUBLISHED template revision can be instantiated");
        }
        String key = required(idempotencyKey, "Idempotency-Key");
        var existing = repository.findInstanceByIdempotency(templateId, key);
        if (existing.isPresent()) return existing.get();
        UUID instanceId = UUID.randomUUID();
        Instant now = clock.instant();
        String hash = sha256(templateId + "\u0000" + revision + "\u0000" + key);
        WorkerTemplateInstance pending = new WorkerTemplateInstance(instanceId, templateId, revision, null, null,
                "PENDING", revision, key, hash, now, now, 0);
        repository.insertInstance(pending);
        try {
            TemplateInstanceProvisioner.ProvisionedInstance provisioned = provisioner.provision(published, instanceId, key);
            WorkerTemplateInstance succeeded = new WorkerTemplateInstance(instanceId, templateId, revision,
                    provisioned.agentSpecId(), provisioned.workerId(), "SUCCEEDED", revision, key, hash, now,
                    clock.instant(), 1);
            return repository.updateInstance(succeeded, 0);
        } catch (RuntimeException failure) {
            WorkerTemplateInstance failed = new WorkerTemplateInstance(instanceId, templateId, revision, null, null,
                    "FAILED", revision, key, hash, now, clock.instant(), 1);
            repository.updateInstance(failed, 0);
            return failed;
        }
    }

    public WorkerTemplateInstance instance(UUID templateId, UUID instanceId) {
        get(templateId);
        return repository.findInstance(templateId, instanceId)
                .orElseThrow(() -> new TemplateConflictException("worker template instance does not exist"));
    }

    public List<WorkerTemplateInstance> instances(UUID templateId) {
        get(templateId);
        return repository.findInstances(templateId);
    }

    public WorkerTemplateInstance upgrade(UUID templateId, UUID instanceId, long targetRevision,
            String idempotencyKey) {
        WorkerTemplateInstance current = instance(templateId, instanceId);
        WorkerTemplateRevision target = revision(templateId, targetRevision);
        if (target.status() != TemplateStatus.PUBLISHED || targetRevision <= current.currentTemplateRevision()) {
            throw new TemplateConflictException("instance upgrade requires a newer PUBLISHED revision");
        }
        String key = required(idempotencyKey, "Idempotency-Key");
        String requestHash = sha256(templateId + "\u0000" + instanceId + "\u0000" + targetRevision);
        TemplateInstanceProvisioner.ProvisionedInstance provisioned = provisioner.upgrade(current, target, key);
        WorkerTemplateInstance upgraded = new WorkerTemplateInstance(current.id(), current.templateId(),
                current.templateRevision(), provisioned.agentSpecId(), provisioned.workerId(), "SUCCEEDED",
                targetRevision, current.idempotencyKey(), current.requestHash(), current.createdAt(), clock.instant(),
                current.version() + 1);
        return repository.upgradeInstance(upgraded, current.version(), targetRevision, key, requestHash);
    }

    public record CreateInput(String name, String displayName) { }

    private void requireVisible(WorkerTemplate template) {
        Principal principal = principal();
        if (!template.tenantId().equals(principal.scope().tenant())
                || !template.projectId().equals(principal.scope().project())) {
            throw new AuthorizationException("worker template is outside caller project");
        }
    }

    private static Principal principal() {
        return PrincipalContext.current().orElseThrow(() -> new AuthorizationException("authentication required"));
    }

    private static String canonicalObject(String value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("template spec must be a JSON object");
            return ConfigManifestCanonicalizer.normalize(node.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("template spec must be valid JSON object", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
