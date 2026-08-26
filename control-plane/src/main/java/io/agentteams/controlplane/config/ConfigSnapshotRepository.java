package io.agentteams.controlplane.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ConfigSnapshotRepository {
    private final JdbcTemplate jdbc;

    public ConfigSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc"); }

    public boolean insertIfAbsent(ConfigSnapshot snapshot) {
        return jdbc.update("""
                INSERT INTO config_snapshots (id, subject, version, manifest, checksum, actor, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.subject(), snapshot.version(), snapshot.manifestJson(),
                snapshot.checksum(), snapshot.actor(), java.sql.Timestamp.from(snapshot.createdAt())) == 1;
    }

    public boolean insertIfAbsent(ConfigSnapshot snapshot, String idempotencyKey, String requestHash) {
        return jdbc.update("""
                INSERT INTO config_snapshots (id, subject, version, manifest, checksum, actor, created_at,
                    idempotency_key, request_hash, provenance)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.subject(), snapshot.version(), snapshot.manifestJson(),
                snapshot.checksum(), snapshot.actor(), java.sql.Timestamp.from(snapshot.createdAt()),
                idempotencyKey, requestHash, provenanceJson(snapshot.provenance())) == 1;
    }

    public Optional<ConfigSnapshot> findByIdempotencyKey(String subject, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, subject, version, manifest::text, checksum, actor, created_at,
                       idempotency_key, request_hash, provenance::text
                  FROM config_snapshots WHERE subject = ? AND idempotency_key = ?
                """, this::map, subject, idempotencyKey).stream().findFirst();
    }

    public Optional<ConfigSnapshot> findBySubjectAndVersion(String subject, long version) {
        return jdbc.query("""
                SELECT id, subject, version, manifest::text, checksum, actor, created_at,
                       idempotency_key, request_hash, provenance::text
                FROM config_snapshots WHERE subject = ? AND version = ?
                """, this::map, subject, version).stream().findFirst();
    }

    public Optional<ConfigSnapshot> findById(UUID id) {
        return jdbc.query("""
                SELECT id, subject, version, manifest::text, checksum, actor, created_at,
                       idempotency_key, request_hash, provenance::text
                FROM config_snapshots WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public Optional<ConfigSnapshot> findByChecksum(String subject, String checksum) {
        return jdbc.query("""
                SELECT id, subject, version, manifest::text, checksum, actor, created_at,
                       idempotency_key, request_hash, provenance::text
                FROM config_snapshots WHERE subject = ?
                """, this::map, subject).stream()
                .filter(snapshot -> snapshot.checksum().equals(checksum)).findFirst();
    }

    public long nextVersion(String subject) {
        Long version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM config_snapshots WHERE subject = ?",
                Long.class, subject);
        return version == null ? 1 : version;
    }

    private ConfigSnapshot map(ResultSet rs, int row) throws SQLException {
        return new ConfigSnapshot(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getLong("version"), normalize(rs.getString("manifest")),
                ConfigManifestCanonicalizer.normalize(rs.getString("manifest")).equals(rs.getString("manifest"))
                        ? rs.getString("checksum") : checksum(rs.getString("manifest")),
                rs.getString("actor"), rs.getTimestamp("created_at").toInstant(), provenance(column(rs, "provenance")),
                column(rs, "idempotency_key"), column(rs, "request_hash"));
    }

    private static String column(ResultSet rs, String name) {
        try { return rs.getString(name); } catch (SQLException ignored) { return null; }
    }

    private static ConfigProvenance provenance(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, ConfigProvenance.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("stored provenance is invalid", error);
        }
    }

    private static String provenanceJson(ConfigProvenance provenance) {
        if (provenance == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(provenance);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("provenance cannot be serialized", error);
        }
    }

    private static String normalize(String manifest) {
        return ConfigManifestCanonicalizer.normalize(manifest);
    }

    private static String checksum(String manifest) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalize(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
