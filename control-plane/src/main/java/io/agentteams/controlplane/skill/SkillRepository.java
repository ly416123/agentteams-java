package io.agentteams.controlplane.skill;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public class SkillRepository {

    private static final String CREATE_SKILL = "CREATE_SKILL";
    private static final String CREATE_VERSION = "CREATE_SKILL_VERSION";
    private final JdbcTemplate jdbc;

    public SkillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SkillRecord createSkill(SkillRecord skill, String idempotencyKey, String requestHash) {
        UUID resourceId = reserveIdempotency(idempotencyKey, CREATE_SKILL, requestHash, skill.id());
        if (!resourceId.equals(skill.id())) {
            return findById(resourceId).orElseThrow(() -> new IllegalStateException(
                    "idempotency record points to a missing skill " + resourceId));
        }
        jdbc.update("""
                INSERT INTO skills (id, name, display_name, description, visibility, lifecycle,
                                    created_at, updated_at, version, organization_id, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, skill.id(), skill.name(), skill.displayName(), skill.description(), skill.visibility(),
                skill.lifecycle(), timestamp(skill.createdAt()), timestamp(skill.updatedAt()), skill.version(),
                skill.organizationId(), skill.tenantId());
        return skill;
    }

    public SkillVersionRecord createVersion(SkillVersionRecord skillVersion, String idempotencyKey,
            String requestHash) {
        UUID resourceId = reserveIdempotency(idempotencyKey, CREATE_VERSION, requestHash, skillVersion.id());
        if (!resourceId.equals(skillVersion.id())) {
            return findVersionById(resourceId).orElseThrow(() -> new IllegalStateException(
                    "idempotency record points to a missing skill version " + resourceId));
        }
        jdbc.update("""
                INSERT INTO skill_versions (id, skill_id, version, digest, manifest, visibility, lifecycle,
                                            created_at, updated_at, record_version, security_scan_status, review_status,
                                            package_storage_key, package_size_bytes, package_sha256, package_upload_status,
                                            organization_id, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, skillVersion.id(), skillVersion.skillId(), skillVersion.version(), skillVersion.digest(),
                json(skillVersion.manifestJson()), skillVersion.visibility(), skillVersion.lifecycle(),
                timestamp(skillVersion.createdAt()), timestamp(skillVersion.updatedAt()), skillVersion.recordVersion(),
                skillVersion.securityScanStatus(), skillVersion.reviewStatus(), skillVersion.packageStorageKey(),
                skillVersion.packageSizeBytes(), skillVersion.packageSha256(), skillVersion.packageUploadStatus(),
                skillVersion.organizationId(), skillVersion.tenantId());
        return skillVersion;
    }

    public List<SkillRecord> findAll() {
        return jdbc.query("""
                SELECT id, name, display_name, description, visibility, lifecycle,
                       created_at, updated_at, version, organization_id, tenant_id
                  FROM skills ORDER BY name, id
                """, this::mapSkill);
    }

    public Optional<SkillRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, name, display_name, description, visibility, lifecycle,
                       created_at, updated_at, version, organization_id, tenant_id
                  FROM skills WHERE id = ?
                """, this::mapSkill, id).stream().findFirst();
    }

    public List<SkillVersionRecord> findVersions(UUID skillId) {
        return jdbc.query("""
                SELECT id, skill_id, version, digest, manifest::text, visibility, lifecycle,
                       created_at, updated_at, record_version, security_scan_status, review_status,
                       package_storage_key, package_size_bytes, package_sha256, package_upload_status,
                       organization_id, tenant_id
                  FROM skill_versions WHERE skill_id = ? ORDER BY created_at, id
                """, this::mapVersion, skillId);
    }

    public Optional<SkillVersionRecord> findVersionById(UUID id) {
        return jdbc.query("""
                SELECT id, skill_id, version, digest, manifest::text, visibility, lifecycle,
                       created_at, updated_at, record_version, security_scan_status, review_status,
                       package_storage_key, package_size_bytes, package_sha256, package_upload_status,
                       organization_id, tenant_id
                  FROM skill_versions WHERE id = ?
                """, this::mapVersion, id).stream().findFirst();
    }

    public SkillVersionRecord publish(UUID skillId, UUID versionId, Instant updatedAt) {
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions
                   SET lifecycle = 'DISABLED', updated_at = ?, record_version = record_version + 1
                 WHERE skill_id = ? AND lifecycle = 'PUBLISHED' AND id <> ?
                """, timestamp(updatedAt), skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions
                   SET lifecycle = 'PUBLISHED', updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, timestamp(updatedAt), versionId, skillId);
        jdbc.update("""
                UPDATE skills SET lifecycle = 'PUBLISHED', updated_at = ?, version = version + 1
                 WHERE id = ?
                """, timestamp(updatedAt), skillId);
        return findVersionById(version.id()).orElseThrow();
    }

    public SkillVersionRecord markSecurityScan(UUID skillId, UUID versionId, String status, Instant updatedAt) {
        versionForSkill(skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions SET security_scan_status = ?, updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, status, timestamp(updatedAt), versionId, skillId);
        return findVersionById(versionId).orElseThrow();
    }

    public SkillVersionRecord markPackageUploadPending(UUID skillId, UUID versionId, String storageKey,
            long sizeBytes, String sha256, Instant updatedAt) {
        versionForSkill(skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions
                   SET package_storage_key = ?, package_size_bytes = ?, package_sha256 = ?,
                       package_upload_status = 'PENDING', updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, storageKey, sizeBytes, sha256, timestamp(updatedAt), versionId, skillId);
        return findVersionById(versionId).orElseThrow();
    }

    public SkillVersionRecord completePackageUpload(UUID skillId, UUID versionId, long sizeBytes,
            String sha256, Instant updatedAt) {
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        if (!SkillPackageStoragePaths.isVersionPackage(skillId, versionId, version.packageStorageKey())) {
            throw new IllegalArgumentException("skill package storage key is invalid");
        }
        jdbc.update("""
                UPDATE skill_versions
                   SET package_size_bytes = ?, package_sha256 = ?, package_upload_status = 'COMPLETED',
                       updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, sizeBytes, sha256, timestamp(updatedAt), versionId, skillId);
        return findVersionById(versionId).orElseThrow();
    }

    public SkillVersionRecord review(UUID skillId, UUID versionId, String status, Instant updatedAt) {
        versionForSkill(skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions SET review_status = ?, updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, status, timestamp(updatedAt), versionId, skillId);
        return findVersionById(versionId).orElseThrow();
    }

    public SkillVersionRecord disable(UUID skillId, UUID versionId, Instant updatedAt) {
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        jdbc.update("""
                UPDATE skill_versions
                   SET lifecycle = 'DISABLED', updated_at = ?, record_version = record_version + 1
                 WHERE id = ? AND skill_id = ?
                """, timestamp(updatedAt), versionId, skillId);
        boolean publishedVersionRemains = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM skill_versions
                                WHERE skill_id = ? AND lifecycle = 'PUBLISHED')
                """, Boolean.class, skillId));
        jdbc.update("""
                UPDATE skills SET lifecycle = ?, updated_at = ?, version = version + 1
                 WHERE id = ?
                """, publishedVersionRemains ? "PUBLISHED" : "DISABLED", timestamp(updatedAt), skillId);
        return findVersionById(version.id()).orElseThrow();
    }

    private SkillVersionRecord versionForSkill(UUID skillId, UUID versionId) {
        SkillVersionRecord version = findVersionById(versionId).orElseThrow(() ->
                new IllegalArgumentException("skill version " + versionId + " was not found"));
        if (!skillId.equals(version.skillId())) {
            throw new IllegalArgumentException("skill version does not belong to skill");
        }
        return version;
    }

    private UUID reserveIdempotency(String key, String operation, String requestHash, UUID resourceId) {
        int inserted = jdbc.update("""
                INSERT INTO skill_idempotency_keys (idempotency_key, operation, request_hash, resource_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, operation, requestHash, resourceId, timestamp(Instant.now()));
        if (inserted == 1) {
            return resourceId;
        }
        IdempotencyRecord existing = jdbc.query("""
                SELECT operation, request_hash, resource_id
                  FROM skill_idempotency_keys WHERE idempotency_key = ?
                """, (rs, row) -> new IdempotencyRecord(rs.getString("operation"),
                        rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)), key)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "idempotency reservation disappeared for key " + key));
        if (!operation.equals(existing.operation()) || !requestHash.equals(existing.requestHash())) {
            throw new SkillIdempotencyConflictException(key, operation);
        }
        return existing.resourceId();
    }

    private SkillRecord mapSkill(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SkillRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("display_name"), rs.getString("description"), rs.getString("visibility"),
                rs.getString("lifecycle"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("organization_id"), rs.getString("tenant_id"));
    }

    private SkillVersionRecord mapVersion(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SkillVersionRecord(rs.getObject("id", UUID.class), rs.getObject("skill_id", UUID.class),
                rs.getString("version"), rs.getString("digest"), rs.getString("manifest"),
                rs.getString("visibility"), rs.getString("lifecycle"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("record_version"),
                rs.getString("security_scan_status"), rs.getString("review_status"),
                rs.getString("package_storage_key"),
                (Long) rs.getObject("package_size_bytes"), rs.getString("package_sha256"),
                rs.getString("package_upload_status"), rs.getString("organization_id"), rs.getString("tenant_id"));
    }

    private static SqlParameterValue json(String value) {
        return new SqlParameterValue(Types.OTHER, value);
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private record IdempotencyRecord(String operation, String requestHash, UUID resourceId) {
    }
}
