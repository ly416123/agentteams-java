package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.observability.ControlPlaneMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Binds a desired snapshot to an Agent and emits a durable ConfigChanged command. */
public final class ConfigDeploymentService {
    public static final String CONFIG_CHANGED = "ConfigChanged";
    private static final String CONFIG_DEPLOY = "CONFIG_DEPLOY";
    private static final String CONFIG_RETRY = "CONFIG_RETRY";
    private static final String CONFIG_ROLLBACK = "CONFIG_ROLLBACK";
    private static final int INLINE_MANIFEST_LIMIT_BYTES = 64 * 1024;

    private final FoundationPersistenceService persistence;
    private final ConfigSnapshotRepository snapshots;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final ControlPlaneMetrics metrics;

    public ConfigDeploymentService(FoundationPersistenceService persistence, ConfigSnapshotRepository snapshots,
            Clock clock, ObjectMapper mapper) {
        this(persistence, snapshots, clock, mapper, null);
    }

    public ConfigDeploymentService(FoundationPersistenceService persistence, ConfigSnapshotRepository snapshots,
            Clock clock, ObjectMapper mapper, ControlPlaneMetrics metrics) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.metrics = metrics;
    }

    public ConfigDeployment deploy(UUID agentId, String subject, ConfigSnapshot snapshot) {
        Objects.requireNonNull(agentId, "agentId");
        requireText(subject, "subject");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!subject.equals(snapshot.subject())) {
            throw new IllegalArgumentException("snapshot subject does not match deployment subject");
        }
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            if (tx.agents().findById(agentId).isEmpty()) {
                throw new IllegalArgumentException("agent does not exist: " + agentId);
            }
            ConfigBindingRecord existingBinding = tx.configLifecycle().findBinding(subject, agentId).orElse(null);
            if (existingBinding != null) {
                ConfigSnapshot current = snapshots.findById(existingBinding.snapshotId())
                        .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
                if (current.version() > snapshot.version()) {
                    throw new IllegalArgumentException("config deployment revision is stale");
                }
            }
            ConfigBindingRecord binding = existingBinding
                    == null ? new ConfigBindingRecord(UUID.randomUUID(), subject, agentId, snapshot.id(), now)
                    : new ConfigBindingRecord(existingBinding.id(), subject, agentId, snapshot.id(), now);
            UUID eventId = eventId(binding.id(), snapshot.id());
            if (existingBinding != null && existingBinding.snapshotId().equals(snapshot.id())
                    && tx.outboxEvents().findByEventId(eventId).isPresent()) {
                return new ConfigDeployment(binding, snapshot, eventId);
            }
            tx.configLifecycle().upsertBindingIfNewer(binding, snapshot.version());
            ConfigBindingRecord currentBinding = tx.configLifecycle().findBinding(subject, agentId).orElse(binding);
            if (!currentBinding.snapshotId().equals(snapshot.id())) {
                throw new IllegalArgumentException("config deployment revision is stale");
            }
            ConfigApplyRecord pending = new ConfigApplyRecord(eventId, currentBinding.id(), agentId, snapshot.id(),
                    "PENDING", null, null, now, snapshot.version(), null, false);
            tx.configLifecycle().recordApply(pending);
            String payload = payload(eventId, currentBinding, snapshot, tx.configLifecycle().findFiles(snapshot.id()), false);
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", agentId, CONFIG_CHANGED, payload,
                    now, snapshot.version());
            return new ConfigDeployment(binding, snapshot, eventId);
        });
    }

    public ConfigDeployment deploy(UUID agentId, String subject, ConfigSnapshot snapshot, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        return deployInternal(agentId, subject, snapshot, key);
    }

    private ConfigDeployment deployInternal(UUID agentId, String subject, ConfigSnapshot snapshot, String key) {
        Objects.requireNonNull(agentId, "agentId");
        requireText(subject, "subject");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!subject.equals(snapshot.subject())) throw new IllegalArgumentException("snapshot subject does not match deployment subject");
        String hash = sha256(agentId + "\u0000" + subject + "\u0000" + snapshot.id() + "\u0000" + snapshot.version()
                + "\u0000" + snapshot.checksum());
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            if (key != null) {
                var existing = tx.idempotencyKeys().findByKey(key);
                if (existing.isPresent()) {
                    assertIdempotency(existing.get(), CONFIG_DEPLOY, hash, key);
                    return deploymentFromIdempotency(existing.get(), tx, snapshot);
                }
            }
            if (tx.agents().findById(agentId).isEmpty()) throw new IllegalArgumentException("agent does not exist: " + agentId);
            ConfigBindingRecord existingBinding = tx.configLifecycle().findBinding(subject, agentId).orElse(null);
            if (existingBinding != null) {
                ConfigSnapshot current = snapshots.findById(existingBinding.snapshotId())
                        .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
                if (current.version() > snapshot.version()) throw new IllegalArgumentException("config deployment revision is stale");
            }
            ConfigBindingRecord binding = existingBinding == null
                    ? new ConfigBindingRecord(UUID.randomUUID(), subject, agentId, snapshot.id(), now)
                    : new ConfigBindingRecord(existingBinding.id(), subject, agentId, snapshot.id(), now);
            UUID eventId = eventId(binding.id(), snapshot.id());
            if (key != null && !claimIdempotency(tx, key, CONFIG_DEPLOY, hash, binding.id(), eventId, now)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key).orElseThrow();
                assertIdempotency(winner, CONFIG_DEPLOY, hash, key);
                return deploymentFromIdempotency(winner, tx, snapshot);
            }
            if (existingBinding != null && existingBinding.snapshotId().equals(snapshot.id())
                    && tx.outboxEvents().findByEventId(eventId).isPresent()) {
                return new ConfigDeployment(binding, snapshot, eventId);
            }
            tx.configLifecycle().upsertBindingIfNewer(binding, snapshot.version());
            ConfigBindingRecord currentBinding = tx.configLifecycle().findBinding(subject, agentId).orElse(binding);
            if (!currentBinding.snapshotId().equals(snapshot.id())) throw new IllegalArgumentException("config deployment revision is stale");
            ConfigApplyRecord pending = new ConfigApplyRecord(eventId, currentBinding.id(), agentId, snapshot.id(),
                    "PENDING", null, null, now, snapshot.version(), null, false);
            tx.configLifecycle().recordApply(pending);
            String payload = payload(eventId, currentBinding, snapshot, tx.configLifecycle().findFiles(snapshot.id()), false);
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", agentId, CONFIG_CHANGED, payload, now, snapshot.version());
            return new ConfigDeployment(currentBinding, snapshot, eventId);
        });
    }

    public void recordApplied(ConfigAppliedCommand command) {
        Objects.requireNonNull(command, "command");
        ConfigSnapshot snapshot = snapshots.findById(command.snapshotId())
                .orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
        if (snapshot.version() != command.configVersion()) {
            throw new IllegalArgumentException("config acknowledgement version does not match snapshot");
        }
        Instant now = clock.instant();
        persistence.inTransaction(tx -> {
            ConfigBindingRecord binding = tx.configLifecycle().findBinding(command.bindingId())
                    .orElseThrow(() -> new IllegalArgumentException("config binding does not exist"));
            if (!binding.agentId().equals(command.agentId()) || !binding.snapshotId().equals(command.snapshotId())) {
                throw new IllegalArgumentException("config acknowledgement does not match binding");
            }
            String phase = command.applied() ? "APPLIED" : "FAILED";
            ConfigApplyRecord existing = tx.configLifecycle().findApply(binding.id(), command.snapshotId()).orElse(null);
            if (existing != null && !existing.id().equals(command.eventId())) {
                throw new IllegalArgumentException("config acknowledgement eventId does not match pending apply");
            }
            if (existing != null && existing.observedVersion() != null
                    && existing.observedVersion() != command.configVersion()) {
                throw new IllegalArgumentException("config acknowledgement generation is stale");
            }
            if (existing != null && ("APPLIED".equals(existing.phase())
                    || (existing.phase().equals(phase)
                    && Objects.equals(existing.errorMessage(), command.errorMessage())))) {
                return null;
            }
            Instant appliedAt = command.applied() ? command.occurredAt() : null;
            ConfigApplyRecord record = new ConfigApplyRecord(command.eventId(), binding.id(), command.agentId(),
                    command.snapshotId(), phase, command.errorMessage(), appliedAt, now, command.configVersion(),
                    ConfigFailureClassifier.classify(command.errorMessage()), existing != null && existing.rollback());
            tx.configLifecycle().recordApply(record);
            for (ConfigEventPort.ResourceApplyResult resource : command.resourceResults()) {
                tx.configLifecycle().recordResourceApply(new ResourceApplyRecord(binding.id(), binding.snapshotId(),
                        command.agentId(), command.configVersion(), resource.type(), resource.resourceId(),
                        resource.revision(), resource.expectedDigest(), resource.observedDigest(), resource.status(),
                        resource.failureCategory(), command.occurredAt()));
            }
            if (metrics != null) {
                if (command.applied()) metrics.configApplyAcknowledged();
                else metrics.configApplyFailed();
                if (record.rollback()) {
                    if (command.applied()) metrics.configRollbackCompleted();
                    else metrics.configRollbackFailed();
                }
            }
            return null;
        });
    }

    public java.util.Optional<ConfigBindingStatus> findBindingStatus(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId");
        return persistence.inTransaction(tx -> tx.configLifecycle().findBindingStatus(bindingId));
    }

    /** Re-emits the current failed revision; repeated calls for one failed state are idempotent. */
    public ConfigDeployment retry(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId");
        return retry(bindingId, "legacy-config-retry-" + bindingId);
    }

    public ConfigDeployment retry(UUID bindingId, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        return persistence.inTransaction(tx -> {
            ConfigBindingRecord binding = tx.configLifecycle().findBindingForUpdate(bindingId)
                    .orElseThrow(() -> new IllegalArgumentException("config binding does not exist"));
            ConfigSnapshot snapshot = snapshots.findById(binding.snapshotId())
                    .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
            ConfigApplyRecord apply = tx.configLifecycle().findApply(binding.id(), snapshot.id())
                    .orElseThrow(() -> new IllegalArgumentException("config binding has no apply result"));
            String hash = sha256(binding.id() + "\u0000" + snapshot.id() + "\u0000" + snapshot.version());
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CONFIG_RETRY, hash, key);
                return new ConfigDeployment(binding, snapshot,
                        eventIdFrom(existing.get().responsePayloadJson(), binding.id(),
                                replayEventId(binding.id(), snapshot.id(), apply.updatedAt())));
            }
            if (!"FAILED".equals(apply.phase())) throw new IllegalArgumentException("only failed config deployments can be retried");
            UUID eventId = replayEventId(binding.id(), snapshot.id(), apply.updatedAt());
            if (!claimIdempotency(tx, key, CONFIG_RETRY, hash, binding.id(), eventId, clock.instant())) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key).orElseThrow();
                assertIdempotency(winner, CONFIG_RETRY, hash, key);
                return new ConfigDeployment(binding, snapshot,
                        eventIdFrom(winner.responsePayloadJson(), binding.id(),
                                replayEventId(binding.id(), snapshot.id(), apply.updatedAt())));
            }
            if (tx.outboxEvents().findByEventId(eventId).isPresent()) return new ConfigDeployment(binding, snapshot, eventId);
            tx.configLifecycle().markApplyPending(binding.id(), binding.agentId(), snapshot.id(), eventId,
                    clock.instant(), snapshot.version());
            String payload = payload(eventId, binding, snapshot, tx.configLifecycle().findFiles(snapshot.id()), false);
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", binding.agentId(), CONFIG_CHANGED, payload,
                    clock.instant(), snapshot.version());
            return new ConfigDeployment(binding, snapshot, eventId);
        });
    }

    /** Selects the newest previously applied snapshot and emits a durable rollback command. */
    public ConfigDeployment rollback(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId");
        return rollback(bindingId, "legacy-config-rollback-" + bindingId);
    }

    public ConfigDeployment rollback(UUID bindingId, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        return persistence.inTransaction(tx -> {
            ConfigBindingRecord binding = tx.configLifecycle().findBindingForUpdate(bindingId)
                    .orElseThrow(() -> new IllegalArgumentException("config binding does not exist"));
            ConfigSnapshot current = snapshots.findById(binding.snapshotId())
                    .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
            ConfigSnapshot stable = tx.configLifecycle().findLatestAppliedSnapshotForRollback(binding.id(), current.id())
                    .orElseThrow(() -> new IllegalArgumentException("config binding has no stable revision to roll back to"));
            String hash = sha256(binding.id() + "\u0000" + current.id() + "\u0000" + stable.id() + "\u0000" + stable.version());
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CONFIG_ROLLBACK, hash, key);
                return new ConfigDeployment(binding, stable,
                        eventIdFrom(existing.get().responsePayloadJson(), binding.id(),
                                rollbackEventId(binding.id(), current.id(), stable.id())));
            }
            UUID eventId = rollbackEventId(binding.id(), current.id(), stable.id());
            if (!claimIdempotency(tx, key, CONFIG_ROLLBACK, hash, binding.id(), eventId, clock.instant())) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key).orElseThrow();
                assertIdempotency(winner, CONFIG_ROLLBACK, hash, key);
                return new ConfigDeployment(binding, stable,
                        eventIdFrom(winner.responsePayloadJson(), binding.id(),
                                rollbackEventId(binding.id(), current.id(), stable.id())));
            }
            ConfigBindingRecord target = new ConfigBindingRecord(binding.id(), binding.subject(), binding.agentId(), stable.id(), clock.instant());
            if (tx.outboxEvents().findByEventId(eventId).isPresent()) return new ConfigDeployment(target, stable, eventId);
            tx.configLifecycle().upsertBinding(target);
            tx.configLifecycle().markApplyPending(binding.id(), binding.agentId(), stable.id(), eventId,
                    clock.instant(), stable.version());
            tx.configLifecycle().markRollbackRequested(binding.id(), stable.id());
            if (metrics != null) metrics.configRollbackRequested();
            String payload = payload(eventId, target, stable, tx.configLifecycle().findFiles(stable.id()), true);
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", binding.agentId(), CONFIG_CHANGED, payload,
                    clock.instant(), stable.version());
            return new ConfigDeployment(target, stable, eventId);
        });
    }

    private String payload(UUID eventId, ConfigBindingRecord binding, ConfigSnapshot snapshot,
            List<ConfigFileRecord> files, boolean rollback) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("eventId", eventId.toString());
            root.put("agentId", binding.agentId().toString());
            root.put("bindingId", binding.id().toString());
            root.put("snapshotId", snapshot.id().toString());
            root.put("configVersion", snapshot.version());
            root.put("rollback", rollback);
            root.put("manifestUri", "urn:agentteams:config:" + snapshot.id());
            byte[] manifestBytes = snapshot.manifestJson().getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length <= INLINE_MANIFEST_LIMIT_BYTES) {
                root.put("manifestJson", snapshot.manifestJson());
            }
            root.put("manifestSha256", snapshot.checksum());
            root.put("sizeBytes", manifestBytes.length);
            var fileNodes = root.putArray("files");
            for (ConfigFileRecord file : files) {
                ObjectNode node = fileNodes.addObject();
                node.put("path", file.path());
                node.put("uri", "urn:agentteams:config-file:" + snapshot.id() + ":" + file.path());
                node.put("sha256", file.checksum());
                node.put("sizeBytes", file.sizeBytes());
                node.put("contentType", file.contentType());
            }
            return mapper.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("unable to serialize ConfigChanged payload", error);
        }
    }

    public static UUID eventId(UUID bindingId, UUID snapshotId) {
        return UUID.nameUUIDFromBytes((CONFIG_CHANGED + ":" + bindingId + ":" + snapshotId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID replayEventId(UUID bindingId, UUID snapshotId, Instant failedAt) {
        return UUID.nameUUIDFromBytes((CONFIG_CHANGED + ":replay:" + bindingId + ":" + snapshotId + ":" + failedAt)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID rollbackEventId(UUID bindingId, UUID currentSnapshotId, UUID stableSnapshotId) {
        return UUID.nameUUIDFromBytes((CONFIG_CHANGED + ":rollback:" + bindingId + ":"
                + currentSnapshotId + ":" + stableSnapshotId).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static String requireKey(String value) {
        requireText(value, "Idempotency-Key");
        return value;
    }

    private static boolean claimIdempotency(FoundationTransaction tx, String key, String operation, String hash,
            UUID resourceId, UUID eventId, Instant now) {
        return tx.idempotencyKeys().insertIfAbsent(new IdempotencyKeyRecord(UUID.randomUUID(), key, operation, hash,
                "config-binding", resourceId, "{\"eventId\":\"" + eventId + "\"}", now, now, 0));
    }

    private ConfigDeployment deploymentFromIdempotency(IdempotencyKeyRecord record, FoundationTransaction tx,
            ConfigSnapshot fallbackSnapshot) {
        ConfigBindingRecord binding = tx.configLifecycle().findBinding(record.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent config binding is missing"));
        ConfigSnapshot snapshot = snapshots.findById(binding.snapshotId()).orElse(fallbackSnapshot);
        UUID eventId = eventIdFrom(record.responsePayloadJson(), binding.id(), snapshot.id());
        return new ConfigDeployment(binding, snapshot, eventId);
    }

    private UUID eventIdFrom(String payload, UUID bindingId, UUID fallbackSnapshotId) {
        try {
            JsonNode node = mapper.readTree(payload);
            String value = node.path("eventId").asText(null);
            return value == null ? fallbackSnapshotId : UUID.fromString(value);
        } catch (Exception ignored) {
            return fallbackSnapshotId;
        }
    }

    private static void assertIdempotency(IdempotencyKeyRecord existing, String operation, String hash, String key) {
        if (!operation.equals(existing.operation()) || !hash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException(key, operation);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record ConfigDeployment(ConfigBindingRecord binding, ConfigSnapshot snapshot, UUID eventId) {
        public ConfigDeployment {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(eventId, "eventId");
        }
    }
}
