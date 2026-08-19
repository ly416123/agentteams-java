package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Binds a desired snapshot to an Agent and emits a durable ConfigChanged command. */
public final class ConfigDeploymentService {
    public static final String CONFIG_CHANGED = "ConfigChanged";
    private static final int INLINE_MANIFEST_LIMIT_BYTES = 64 * 1024;

    private final FoundationPersistenceService persistence;
    private final ConfigSnapshotRepository snapshots;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ConfigDeploymentService(FoundationPersistenceService persistence, ConfigSnapshotRepository snapshots,
            Clock clock, ObjectMapper mapper) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
            ConfigBindingRecord binding = existingBinding
                    == null ? new ConfigBindingRecord(UUID.randomUUID(), subject, agentId, snapshot.id(), now)
                    : new ConfigBindingRecord(existingBinding.id(), subject, agentId, snapshot.id(), now);
            UUID eventId = eventId(binding.id(), snapshot.id());
            if (existingBinding != null && existingBinding.snapshotId().equals(snapshot.id())
                    && tx.outboxEvents().findByEventId(eventId).isPresent()) {
                return new ConfigDeployment(binding, snapshot, eventId);
            }
            tx.configLifecycle().upsertBinding(binding);
            ConfigApplyRecord pending = new ConfigApplyRecord(UUID.randomUUID(), binding.id(), agentId, snapshot.id(),
                    "PENDING", null, null, now);
            tx.configLifecycle().recordApply(pending);
            String payload = payload(eventId, binding, snapshot);
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
            Instant appliedAt = command.applied() ? command.occurredAt() : null;
            ConfigApplyRecord record = new ConfigApplyRecord(command.eventId(), binding.id(), command.agentId(),
                    command.snapshotId(), phase, command.errorMessage(), appliedAt, now);
            tx.configLifecycle().recordApply(record);
            return null;
        });
    }

    private String payload(UUID eventId, ConfigBindingRecord binding, ConfigSnapshot snapshot) {
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
            return mapper.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("unable to serialize ConfigChanged payload", error);
        }
    }

    public static UUID eventId(UUID bindingId, UUID snapshotId) {
        return UUID.nameUUIDFromBytes((CONFIG_CHANGED + ":" + bindingId + ":" + snapshotId)
                .getBytes(StandardCharsets.UTF_8));
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
