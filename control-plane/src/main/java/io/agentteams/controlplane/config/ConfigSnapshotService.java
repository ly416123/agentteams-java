package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ConfigSnapshotService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConfigSnapshotRepository repository;
    private final Clock clock;

    public ConfigSnapshotService(ConfigSnapshotRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConfigSnapshot create(String subject, String manifestJson, String actor) {
        requireText(subject, "subject");
        requireText(actor, "actor");
        String normalized = validateManifest(manifestJson);
        String checksum = sha256(normalized);
        for (int attempt = 0; attempt < 3; attempt++) {
            var existing = repository.findByChecksum(subject, checksum);
            if (existing.isPresent()) return existing.get();
            ConfigSnapshot snapshot = new ConfigSnapshot(UUID.randomUUID(), subject, repository.nextVersion(subject),
                    normalized, checksum, actor, Instant.now(clock));
            if (repository.insertIfAbsent(snapshot)) return snapshot;
        }
        return repository.findByChecksum(subject, checksum)
                .orElseThrow(() -> new IllegalStateException("config snapshot insert lost without winner"));
    }

    public ConfigSnapshot create(String subject, String manifestJson, String actor, String idempotencyKey) {
        return create(subject, manifestJson, actor, idempotencyKey, null);
    }

    public ConfigSnapshot create(String subject, String manifestJson, String actor, String idempotencyKey,
            ConfigProvenance provenance) {
        requireText(subject, "subject");
        requireText(actor, "actor");
        requireText(idempotencyKey, "Idempotency-Key");
        String normalized = validateManifest(manifestJson);
        String checksum = sha256(normalized);
        String requestHash = sha256(subject + "\u0000" + normalized + "\u0000" + actor + "\u0000" + provenance);
        for (int attempt = 0; attempt < 3; attempt++) {
            var keyed = repository.findByIdempotencyKey(subject, idempotencyKey);
            if (keyed.isPresent()) {
                if (!requestHash.equals(keyed.get().requestHash())) {
                    throw new IllegalArgumentException("Idempotency-Key request hash mismatch");
                }
                return keyed.get();
            }
            var existing = repository.findByChecksum(subject, checksum);
            if (existing.isPresent()) return existing.get();
            ConfigSnapshot snapshot = new ConfigSnapshot(UUID.randomUUID(), subject, repository.nextVersion(subject),
                    normalized, checksum, actor, Instant.now(clock), provenance, idempotencyKey, requestHash);
            if (repository.insertIfAbsent(snapshot, idempotencyKey, requestHash)) return snapshot;
        }
        return repository.findByChecksum(subject, checksum)
                .orElseThrow(() -> new IllegalStateException("config snapshot insert lost without winner"));
    }

    private static String validateManifest(String value) {
        requireText(value, "manifestJson");
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("manifest must be a JSON object");
            return ConfigManifestCanonicalizer.normalize(node.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("manifestJson must be valid JSON", error);
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

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
