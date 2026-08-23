package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Binds a desired snapshot to an Agent and emits a durable ConfigChanged command. */
public final class ConfigDeploymentService {
    public static final String CONFIG_CHANGED = "ConfigChanged";
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
                    "PENDING", null, null, now);
            tx.configLifecycle().recordApply(pending);
            String payload = payload(eventId, currentBinding, snapshot, tx.configLifecycle().findFiles(snapshot.id()));
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", agentId, CONFIG_CHANGED, payload,
                    now, snapshot.version());
            return new ConfigDeployment(binding, snapshot, eventId);
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
            if (existing != null && ("APPLIED".equals(existing.phase())
                    || (existing.phase().equals(phase)
                    && Objects.equals(existing.errorMessage(), command.errorMessage())))) {
                return null;
            }
            Instant appliedAt = command.applied() ? command.occurredAt() : null;
            ConfigApplyRecord record = new ConfigApplyRecord(command.eventId(), binding.id(), command.agentId(),
                    command.snapshotId(), phase, command.errorMessage(), appliedAt, now, command.configVersion(),
                    ConfigFailureClassifier.classify(command.errorMessage()));
            tx.configLifecycle().recordApply(record);
            if (metrics != null) {
                if (command.applied()) metrics.configApplyAcknowledged();
                else metrics.configApplyFailed();
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
        return persistence.inTransaction(tx -> {
            ConfigBindingRecord binding = tx.configLifecycle().findBindingForUpdate(bindingId)
                    .orElseThrow(() -> new IllegalArgumentException("config binding does not exist"));
            ConfigSnapshot snapshot = snapshots.findById(binding.snapshotId())
                    .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
            ConfigApplyRecord apply = tx.configLifecycle().findApply(binding.id(), snapshot.id())
                    .orElseThrow(() -> new IllegalArgumentException("config binding has no apply result"));
            if (!"FAILED".equals(apply.phase())) {
                throw new IllegalArgumentException("only failed config deployments can be retried");
            }
            UUID eventId = replayEventId(binding.id(), snapshot.id(), apply.updatedAt());
            if (tx.outboxEvents().findByEventId(eventId).isPresent()) {
                return new ConfigDeployment(binding, snapshot, eventId);
            }
            String payload = payload(eventId, binding, snapshot, tx.configLifecycle().findFiles(snapshot.id()));
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", binding.agentId(), CONFIG_CHANGED, payload,
                    clock.instant(), snapshot.version());
            return new ConfigDeployment(binding, snapshot, eventId);
        });
    }

    /** Selects the newest previously applied snapshot and emits a durable rollback command. */
    public ConfigDeployment rollback(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId");
        return persistence.inTransaction(tx -> {
            ConfigBindingRecord binding = tx.configLifecycle().findBindingForUpdate(bindingId)
                    .orElseThrow(() -> new IllegalArgumentException("config binding does not exist"));
            ConfigSnapshot current = snapshots.findById(binding.snapshotId())
                    .orElseThrow(() -> new IllegalStateException("desired config snapshot does not exist"));
            ConfigSnapshot stable = tx.configLifecycle()
                    .findLatestAppliedSnapshotForRollback(binding.id(), current.id())
                    .orElseThrow(() -> new IllegalArgumentException("config binding has no stable revision to roll back to"));
            UUID eventId = rollbackEventId(binding.id(), current.id(), stable.id());
            ConfigBindingRecord target = new ConfigBindingRecord(binding.id(), binding.subject(), binding.agentId(),
                    stable.id(), clock.instant());
            if (tx.outboxEvents().findByEventId(eventId).isPresent()) {
                return new ConfigDeployment(target, stable, eventId);
            }
            tx.configLifecycle().upsertBinding(target);
            tx.configLifecycle().markApplyPending(binding.id(), binding.agentId(), stable.id(), eventId, clock.instant());
            if (metrics != null) metrics.configRollbackRequested();
            String payload = payload(eventId, target, stable, tx.configLifecycle().findFiles(stable.id()));
            FoundationPersistenceService.appendEvent(tx, eventId, "agent", binding.agentId(), CONFIG_CHANGED, payload,
                    clock.instant(), stable.version());
            return new ConfigDeployment(target, stable, eventId);
        });
    }

    private String payload(UUID eventId, ConfigBindingRecord binding, ConfigSnapshot snapshot,
            List<ConfigFileRecord> files) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("eventId", eventId.toString());
            root.put("agentId", binding.agentId().toString());
            root.put("bindingId", binding.id().toString());
            root.put("snapshotId", snapshot.id().toString());
            root.put("configVersion", snapshot.version());
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

    public record ConfigDeployment(ConfigBindingRecord binding, ConfigSnapshot snapshot, UUID eventId) {
        public ConfigDeployment {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(eventId, "eventId");
        }
    }
}
