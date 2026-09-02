package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.controlplane.persistence.IdempotencyKeyRepository;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.security.SignatureAlgorithm;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed management metadata store. Secret material is never a column. */
@Repository
public class JdbcOrganizationManagementRepository implements OrganizationManagementRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    @Autowired
    public JdbcOrganizationManagementRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    JdbcOrganizationManagementRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    private JdbcOrganizationManagementRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = transaction;
    }

    @Override
    public OrganizationManagementController.OrganizationResponse insertOrganization(UUID id, String externalKey,
            String displayName, Instant now) {
        jdbc.update("""
                INSERT INTO organizations(id, external_key, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, externalKey, displayName, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.OrganizationResponse(id, displayName, "ACTIVE", 0);
    }

    @Override
    public OrganizationManagementController.OrganizationResponse insertOrganizationIdempotent(UUID id, String externalKey,
            String displayName, String idempotencyKey, String requestHash, Instant now) {
        return inTransaction(keys -> {
            var existing = keys.findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), "CREATE_ORGANIZATION", requestHash, idempotencyKey);
                return findOrganization(existing.get().resourceId()).orElseThrow();
            }
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey, "CREATE_ORGANIZATION", requestHash,
                    "organization", id, "{}", now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "CREATE_ORGANIZATION", requestHash, idempotencyKey);
                return findOrganization(winner.resourceId()).orElseThrow();
            }
            return insertOrganization(id, externalKey, displayName, now);
        });
    }

    @Override
    public Optional<OrganizationManagementController.OrganizationResponse> findOrganization(UUID id) {
        return jdbc.query("""
                SELECT id, display_name, status, version FROM organizations WHERE id = ?
                """, (rs, row) -> new OrganizationManagementController.OrganizationResponse(
                rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("version")), id).stream().findFirst();
    }

    @Override
    public List<OrganizationManagementController.OrganizationResponse> listOrganizations() {
        return jdbc.query("SELECT id, display_name, status, version FROM organizations ORDER BY display_name, id",
                (rs, row) -> new OrganizationManagementController.OrganizationResponse(rs.getObject("id", UUID.class),
                        rs.getString("display_name"), rs.getString("status"), rs.getLong("version")));
    }

    @Override
    public OrganizationManagementController.OrganizationResponse updateOrganizationStatus(UUID id, long expectedVersion,
            String status, Instant now) {
        ensureExpectedVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE organizations SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status, JdbcSupport.timestamp(now), id, expectedVersion);
        if (updated != 1) throw new OptimisticLockFailure("organization", id, expectedVersion, organizationVersion(id));
        return findOrganization(id).orElseThrow();
    }

    @Override
    public List<OrganizationManagementController.TenantResponse> listTenants(UUID organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, display_name, status, version FROM tenants
                 WHERE organization_id = ? ORDER BY display_name, id
                """, (rs, row) -> new OrganizationManagementController.TenantResponse(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("version")), organizationId);
    }

    @Override
    public OrganizationManagementController.TenantResponse insertTenant(UUID id, UUID organizationId,
            String externalKey, String displayName, Instant now) {
        jdbc.update("""
                INSERT INTO tenants(id, organization_id, external_key, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, organizationId, externalKey, displayName, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.TenantResponse(id, organizationId, displayName, "ACTIVE", 0);
    }

    @Override
    public OrganizationManagementController.TenantResponse insertTenantIdempotent(UUID id, UUID organizationId,
            String externalKey, String displayName, String idempotencyKey, String requestHash, Instant now) {
        return inTransaction(keys -> {
            var existing = keys.findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), "CREATE_TENANT", requestHash, idempotencyKey);
                return findTenant(existing.get().resourceId()).orElseThrow();
            }
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey, "CREATE_TENANT", requestHash,
                    "tenant", id, "{}", now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "CREATE_TENANT", requestHash, idempotencyKey);
                return findTenant(winner.resourceId()).orElseThrow();
            }
            return insertTenant(id, organizationId, externalKey, displayName, now);
        });
    }

    @Override
    public OrganizationManagementController.TenantResponse updateTenantStatus(UUID id, long expectedVersion,
            String status, Instant now) {
        ensureExpectedVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE tenants SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status, JdbcSupport.timestamp(now), id, expectedVersion);
        if (updated != 1) throw new OptimisticLockFailure("tenant", id, expectedVersion, tenantVersion(id));
        return findTenant(id).orElseThrow();
    }

    private Optional<OrganizationManagementController.TenantResponse> findTenant(UUID id) {
        return jdbc.query("""
                SELECT id, organization_id, display_name, status, version FROM tenants WHERE id = ?
                """, (rs, row) -> new OrganizationManagementController.TenantResponse(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("version")), id).stream().findFirst();
    }

    private long organizationVersion(UUID id) {
        return findOrganization(id).map(OrganizationManagementController.OrganizationResponse::version).orElse(-1L);
    }

    private long tenantVersion(UUID id) {
        return findTenant(id).map(OrganizationManagementController.TenantResponse::version).orElse(-1L);
    }

    private <T> T inTransaction(java.util.function.Function<IdempotencyKeyRepository, T> work) {
        if (transaction == null) return work.apply(new IdempotencyKeyRepository(jdbc));
        return transaction.execute(status -> work.apply(new IdempotencyKeyRepository(jdbc)));
    }

    private static void ensureExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    }

    private static void assertIdempotency(IdempotencyKeyRecord existing, String operation, String requestHash,
            String key) {
        if (!operation.equals(existing.operation()) || !requestHash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException(key, operation);
        }
    }

    @Override
    public OrganizationManagementController.IntegrationResponse insertIntegration(UUID id, UUID organizationId,
            String externalKey, String displayName, Instant now) {
        jdbc.update("""
                INSERT INTO integrations(id, organization_id, external_key, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, organizationId, externalKey, displayName, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.IntegrationResponse(id, organizationId, displayName, "ACTIVE", 0);
    }

    @Override
    public Optional<OrganizationManagementController.IntegrationResponse> findIntegration(UUID id) {
        return jdbc.query("""
                SELECT id, organization_id, display_name, status, version FROM integrations WHERE id = ?
                """, (rs, row) -> new OrganizationManagementController.IntegrationResponse(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("display_name"), rs.getString("status"), rs.getLong("version")), id).stream().findFirst();
    }

    @Override
    public List<OrganizationManagementController.IntegrationResponse> listIntegrations(UUID organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, display_name, status, version FROM integrations
                 WHERE organization_id = ? ORDER BY display_name, id
                """, (rs, row) -> new OrganizationManagementController.IntegrationResponse(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("version")), organizationId);
    }

    @Override
    public void insertDefaultProvisioningPolicy(UUID integrationId, Instant now) {
        jdbc.update("""
                INSERT INTO integration_provisioning_policies
                    (integration_id, allow_auto_create, allow_platform_admin, updated_at, version)
                VALUES (?, FALSE, FALSE, ?, 0)
                """, integrationId, JdbcSupport.timestamp(now));
    }

    @Override
    public OrganizationManagementController.CredentialResponse insertCredential(UUID id, UUID integrationId,
            String label, String accessKeyId, String credentialRef, SignatureAlgorithm algorithm, Instant expiresAt,
            Instant now) {
        jdbc.update("""
                INSERT INTO integration_credentials
                    (id, integration_id, label, access_key_id, credential_ref, algorithm, status, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
                """, id, integrationId, label, accessKeyId, credentialRef, algorithm.name(),
                expiresAt == null ? null : JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now));
        return new OrganizationManagementController.CredentialResponse(id, integrationId, label, accessKeyId, 1,
                "ACTIVE");
    }

    @Override
    public OrganizationManagementController.CredentialResponse insertCredentialIdempotent(UUID id, UUID integrationId,
            String label, String accessKeyId, String credentialRef, SignatureAlgorithm algorithm, Instant expiresAt,
            String idempotencyKey, String requestHash, Instant now) {
        return inTransaction(keys -> {
            var prior = keys.findByKey(idempotencyKey);
            if (prior.isPresent()) {
                assertIdempotency(prior.get(), "CREATE_MANAGEMENT_CREDENTIAL", requestHash, idempotencyKey);
                return findCredentialResponse(prior.get().resourceId());
            }
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    "CREATE_MANAGEMENT_CREDENTIAL", requestHash, "integration_credential", id, "{}", now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "CREATE_MANAGEMENT_CREDENTIAL", requestHash, idempotencyKey);
                return findCredentialResponse(winner.resourceId());
            }
            insertCredential(id, integrationId, label, accessKeyId, credentialRef, algorithm, expiresAt, now);
            return findCredentialResponse(id);
        });
    }

    @Override
    public Optional<CredentialRecord> findCredential(UUID id) {
        return jdbc.query(credentialSelect() + " WHERE id = ?", this::credential, id).stream().findFirst();
    }

    @Override
    public List<OrganizationManagementController.CredentialResponse> listCredentials(UUID integrationId) {
        return jdbc.query(credentialSelect() + " WHERE integration_id = ? ORDER BY created_at, id",
                (rs, row) -> credentialResponse(rs, row),
                integrationId);
    }

    @Override
    public Optional<CredentialRecord> updateCredential(UUID id, long expectedVersion, String accessKeyId,
            String credentialRef, String status, Instant expiresAt, Instant now) {
        var rows = jdbc.query(credentialSelect() + " WHERE id = ? AND version = ?", this::credential, id, expectedVersion);
        if (rows.isEmpty()) {
            long actual = findCredential(id).map(CredentialRecord::version).orElse(-1L);
            throw new OptimisticLockFailure("integration_credential", id, expectedVersion, actual);
        }
        int updated = jdbc.update("""
                UPDATE integration_credentials
                   SET access_key_id = ?, credential_ref = ?, status = ?, expires_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, accessKeyId, credentialRef, status, expiresAt == null ? null : JdbcSupport.timestamp(expiresAt),
                JdbcSupport.timestamp(now), id, expectedVersion);
        if (updated != 1) {
            long actual = findCredential(id).map(CredentialRecord::version).orElse(-1L);
            throw new OptimisticLockFailure("integration_credential", id, expectedVersion, actual);
        }
        return findCredential(id);
    }

    @Override
    public OrganizationManagementController.CredentialResponse updateCredentialIdempotent(UUID id, long expectedVersion,
            String accessKeyId, String credentialRef, String status, Instant expiresAt, String operation,
            String idempotencyKey, String requestHash, Instant now) {
        return inTransaction(keys -> {
            var prior = keys.findByKey(idempotencyKey);
            if (prior.isPresent()) {
                assertIdempotency(prior.get(), operation, requestHash, idempotencyKey);
                return findCredentialResponse(prior.get().resourceId());
            }
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey, operation, requestHash,
                    "integration_credential", id, "{}", now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, operation, requestHash, idempotencyKey);
                return findCredentialResponse(winner.resourceId());
            }
            updateCredential(id, expectedVersion, accessKeyId, credentialRef, status, expiresAt, now).orElseThrow();
            return findCredentialResponse(id);
        });
    }

    private OrganizationManagementController.CredentialResponse findCredentialResponse(UUID id) {
        return findCredential(id).map(this::credentialResponse)
                .orElseThrow(() -> new IllegalStateException("idempotent credential is missing"));
    }

    @Override
    public OrganizationManagementController.ProvisioningPolicyResponse updateProvisioningPolicy(UUID integrationId,
            boolean allowAutoCreate, boolean allowPlatformAdmin, long expectedVersion, Instant now) {
        int updated = jdbc.update("""
                UPDATE integration_provisioning_policies
                   SET allow_auto_create = ?, allow_platform_admin = ?, updated_at = ?, version = version + 1
                 WHERE integration_id = ? AND version = ?
                """, allowAutoCreate, allowPlatformAdmin, JdbcSupport.timestamp(now), integrationId, expectedVersion);
        if (updated != 1) {
            long actual = findProvisioningPolicy(integrationId).map(OrganizationManagementController.ProvisioningPolicyResponse::version)
                    .orElse(-1L);
            throw new OptimisticLockFailure("integration_provisioning_policy", integrationId, expectedVersion, actual);
        }
        return findProvisioningPolicy(integrationId).orElseThrow();
    }

    @Override
    public Optional<OrganizationManagementController.ProvisioningPolicyResponse> findProvisioningPolicy(UUID integrationId) {
        return jdbc.query("""
                SELECT integration_id, allow_auto_create, allow_platform_admin, version
                  FROM integration_provisioning_policies WHERE integration_id = ?
                """, (rs, row) -> new OrganizationManagementController.ProvisioningPolicyResponse(
                rs.getObject("integration_id", UUID.class), rs.getBoolean("allow_auto_create"),
                rs.getBoolean("allow_platform_admin"), rs.getLong("version")), integrationId).stream().findFirst();
    }

    @Override
    public OrganizationManagementController.UserResponse insertUser(UUID id, String subject, String displayName,
            Instant now) {
        jdbc.update("""
                INSERT INTO management_users(id, subject, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, subject, displayName, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.UserResponse(id, subject, displayName, "ACTIVE", 0);
    }

    @Override
    public List<OrganizationManagementController.UserResponse> listUsers() {
        return jdbc.query("""
                SELECT id, subject, display_name, status, version FROM management_users ORDER BY display_name, id
                """, (rs, row) -> new OrganizationManagementController.UserResponse(rs.getObject("id", UUID.class),
                rs.getString("subject"), rs.getString("display_name"), rs.getString("status"), rs.getLong("version")));
    }

    @Override
    public OrganizationManagementController.UserResponse updateUserStatus(UUID id, long expectedVersion, String status,
            Instant now) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
        }
        int updated = jdbc.update("""
                UPDATE management_users SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status, JdbcSupport.timestamp(now), id, expectedVersion);
        if (updated != 1) {
            long actual = jdbc.query("SELECT version FROM management_users WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("management_user", id, expectedVersion, actual);
        }
        return jdbc.query("SELECT id, subject, display_name, status, version FROM management_users WHERE id = ?",
                (rs, row) -> new OrganizationManagementController.UserResponse(rs.getObject("id", UUID.class),
                        rs.getString("subject"), rs.getString("display_name"), rs.getString("status"),
                        rs.getLong("version")), id).stream().findFirst().orElseThrow();
    }

    @Override
    public OrganizationManagementController.MembershipResponse upsertOrganizationMembership(UUID organizationId,
            String subject, String role, Instant now) {
        jdbc.update("""
                INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, subject) DO UPDATE
                    SET role = EXCLUDED.role, updated_at = EXCLUDED.updated_at
                """, organizationId, subject, role, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.MembershipResponse(organizationId, subject, role);
    }

    @Override
    public List<OrganizationManagementController.MembershipResponse> listOrganizationMemberships(UUID organizationId) {
        return jdbc.query("""
                SELECT organization_id, subject, role FROM organization_memberships
                 WHERE organization_id = ? ORDER BY subject
                """, (rs, row) -> new OrganizationManagementController.MembershipResponse(
                rs.getObject("organization_id", UUID.class), rs.getString("subject"), rs.getString("role")),
                organizationId);
    }

    @Override
    public OrganizationManagementController.ExternalIdentityResponse upsertExternalIdentity(UUID id,
            UUID integrationId, UUID organizationId, UUID internalUserId, String externalOrganizationId,
            String externalUserId, Instant now) {
        jdbc.update("""
                INSERT INTO external_identities
                    (id, integration_id, organization_id, internal_user_id, external_organization_id,
                     external_user_id, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                ON CONFLICT (integration_id, external_organization_id, external_user_id) DO UPDATE
                    SET internal_user_id = EXCLUDED.internal_user_id, organization_id = EXCLUDED.organization_id,
                        status = 'ACTIVE', updated_at = EXCLUDED.updated_at, version = external_identities.version + 1
                """, id, integrationId, organizationId, internalUserId, externalOrganizationId, externalUserId,
                JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        return new OrganizationManagementController.ExternalIdentityResponse(id, integrationId, organizationId,
                internalUserId, externalOrganizationId, externalUserId, "ACTIVE");
    }

    @Override
    public List<OrganizationManagementController.ExternalIdentityResponse> listExternalIdentities(UUID integrationId) {
        return jdbc.query("""
                SELECT id, integration_id, organization_id, internal_user_id, external_organization_id,
                       external_user_id, status FROM external_identities
                 WHERE integration_id = ? ORDER BY external_organization_id, external_user_id
                """, (rs, row) -> new OrganizationManagementController.ExternalIdentityResponse(
                rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("internal_user_id", UUID.class),
                rs.getString("external_organization_id"), rs.getString("external_user_id"), rs.getString("status")),
                integrationId);
    }

    @Override
    public OrganizationManagementController.ProvisionedUserResponse provisionExternalUser(UUID integrationId,
            UUID organizationId, String externalOrganizationId, String externalUserId, String displayName,
            String idempotencyKey, String requestHash, Instant now) {
        return inTransaction(keys -> {
            var prior = keys.findByKey(idempotencyKey);
            if (prior.isPresent()) {
                assertIdempotency(prior.get(), "PROVISION_EXTERNAL_USER", requestHash, idempotencyKey);
                return findProvisionedUserByIdentityId(prior.get().resourceId()).orElseThrow();
            }
            var current = findProvisionedUser(integrationId, externalOrganizationId, externalUserId);
            UUID identityId;
            UUID internalUserId;
            if (current.isPresent()) {
                identityId = current.get().identityId();
                internalUserId = current.get().internalUserId();
                jdbc.update("""
                        UPDATE management_users SET display_name = ?, status = 'ACTIVE', updated_at = ?, version = version + 1
                         WHERE id = ?
                        """, displayName, JdbcSupport.timestamp(now), internalUserId);
                jdbc.update("""
                        UPDATE external_identities SET status = 'ACTIVE', updated_at = ?, version = version + 1
                         WHERE id = ?
                        """, JdbcSupport.timestamp(now), identityId);
            } else {
                identityId = UUID.randomUUID();
                internalUserId = UUID.randomUUID();
                String subject = "external:" + integrationId + ":" + externalOrganizationId + ":" + externalUserId;
                jdbc.update("""
                        INSERT INTO management_users(id, subject, display_name, status, created_at, updated_at, version)
                        VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                        """, internalUserId, subject, displayName, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
                jdbc.update("""
                        INSERT INTO external_identities
                            (id, integration_id, organization_id, internal_user_id, external_organization_id,
                             external_user_id, status, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                        """, identityId, integrationId, organizationId, internalUserId, externalOrganizationId,
                        externalUserId, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
            }
            var idempotency = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    "PROVISION_EXTERNAL_USER", requestHash, "external_identity", identityId, "{}", now, now, 0);
            if (!keys.insertIfAbsent(idempotency)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "PROVISION_EXTERNAL_USER", requestHash, idempotencyKey);
                return findProvisionedUserByIdentityId(winner.resourceId()).orElseThrow();
            }
            return findProvisionedUserByIdentityId(identityId).orElseThrow();
        });
    }

    @Override
    public OrganizationManagementController.ProvisionedUserResponse updateProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId, String displayName, String idempotencyKey,
            String requestHash, Instant now) {
        return inTransaction(keys -> {
            var identity = findProvisionedUser(integrationId, externalOrganizationId, externalUserId)
                    .orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
            var prior = keys.findByKey(idempotencyKey);
            if (prior.isPresent()) {
                assertIdempotency(prior.get(), "UPDATE_PROVISIONED_USER", requestHash, idempotencyKey);
                return findProvisionedUserByIdentityId(prior.get().resourceId()).orElseThrow();
            }
            jdbc.update("""
                    UPDATE management_users SET display_name = ?, updated_at = ?, version = version + 1
                     WHERE id = ?
                    """, displayName, JdbcSupport.timestamp(now), identity.internalUserId());
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    "UPDATE_PROVISIONED_USER", requestHash, "external_identity", identity.identityId(), "{}",
                    now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "UPDATE_PROVISIONED_USER", requestHash, idempotencyKey);
            }
            return findProvisionedUserByIdentityId(identity.identityId()).orElseThrow();
        });
    }

    @Override
    public OrganizationManagementController.ProvisionedUserResponse disableProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId, String idempotencyKey, String requestHash,
            Instant now) {
        return inTransaction(keys -> {
            var identity = findProvisionedUser(integrationId, externalOrganizationId, externalUserId)
                    .orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
            var prior = keys.findByKey(idempotencyKey);
            if (prior.isPresent()) {
                assertIdempotency(prior.get(), "DISABLE_PROVISIONED_USER", requestHash, idempotencyKey);
                return findProvisionedUserByIdentityId(prior.get().resourceId()).orElseThrow();
            }
            jdbc.update("""
                    UPDATE external_identities SET status = 'DISABLED', updated_at = ?, version = version + 1
                     WHERE id = ?
                    """, JdbcSupport.timestamp(now), identity.identityId());
            var record = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    "DISABLE_PROVISIONED_USER", requestHash, "external_identity", identity.identityId(), "{}",
                    now, now, 0);
            if (!keys.insertIfAbsent(record)) {
                var winner = keys.findByKey(idempotencyKey).orElseThrow();
                assertIdempotency(winner, "DISABLE_PROVISIONED_USER", requestHash, idempotencyKey);
            }
            return findProvisionedUserByIdentityId(identity.identityId()).orElseThrow();
        });
    }

    @Override
    public List<OrganizationManagementController.ProvisionedMembershipResponse> listProvisionedUserMemberships(
            UUID integrationId, UUID organizationId, String externalOrganizationId, String externalUserId) {
        var identity = findProvisionedUser(integrationId, externalOrganizationId, externalUserId)
                .orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
        String subject = jdbc.query("SELECT subject FROM management_users WHERE id = ?",
                (rs, row) -> rs.getString(1), identity.internalUserId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
        return jdbc.query("""
                SELECT 'organization' AS scope_type, o.id::text AS scope_id, o.display_name AS scope_name, m.role
                  FROM organization_memberships m JOIN organizations o ON o.id = m.organization_id
                 WHERE m.organization_id = ? AND m.subject = ?
                UNION ALL
                SELECT 'tenant' AS scope_type, t.id::text AS scope_id, t.display_name AS scope_name, m.role
                  FROM tenant_memberships m JOIN tenants t ON t.id = m.tenant_id
                 WHERE t.organization_id = ? AND m.subject = ?
                UNION ALL
                SELECT 'project' AS scope_type, p.id::text AS scope_id, p.name AS scope_name, m.role
                  FROM project_memberships m JOIN projects p
                    ON p.id = m.project_id AND p.tenant_id = m.tenant_id
                  JOIN tenants t ON t.id::text = p.tenant_id
                 WHERE t.organization_id = ? AND m.subject = ?
                 ORDER BY scope_type, scope_name, scope_id
                """, (rs, row) -> new OrganizationManagementController.ProvisionedMembershipResponse(
                rs.getString("scope_type"), rs.getString("scope_id"), rs.getString("scope_name"), rs.getString("role")),
                organizationId, subject, organizationId, subject, organizationId, subject);
    }

    private Optional<ProvisionedIdentity> findProvisionedUser(UUID integrationId, String externalOrganizationId,
            String externalUserId) {
        return jdbc.query("""
                SELECT id, internal_user_id FROM external_identities
                 WHERE integration_id = ? AND external_organization_id = ? AND external_user_id = ?
                """, (rs, row) -> new ProvisionedIdentity(rs.getObject("id", UUID.class),
                rs.getObject("internal_user_id", UUID.class)), integrationId, externalOrganizationId, externalUserId)
                .stream().findFirst();
    }

    private Optional<OrganizationManagementController.ProvisionedUserResponse> findProvisionedUserByIdentityId(UUID identityId) {
        return jdbc.query("""
                SELECT e.integration_id, e.external_organization_id, e.external_user_id, e.status,
                       e.internal_user_id, u.display_name
                  FROM external_identities e JOIN management_users u ON u.id = e.internal_user_id
                 WHERE e.id = ?
                """, (rs, row) -> new OrganizationManagementController.ProvisionedUserResponse(
                rs.getObject("integration_id", UUID.class), rs.getString("external_organization_id"),
                rs.getString("external_user_id"), rs.getString("display_name"), rs.getString("status"),
                rs.getObject("internal_user_id", UUID.class)), identityId).stream().findFirst();
    }

    private record ProvisionedIdentity(UUID identityId, UUID internalUserId) { }

    private CredentialRecord credential(ResultSet rs, int row) throws SQLException {
        return new CredentialRecord(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getString("label"), rs.getString("access_key_id"), rs.getString("credential_ref"),
                SignatureAlgorithm.valueOf(rs.getString("algorithm")), rs.getString("status"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("version"));
    }

    private OrganizationManagementController.CredentialResponse credentialResponse(ResultSet rs, int row)
            throws SQLException {
        return new OrganizationManagementController.CredentialResponse(rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class), rs.getString("label"), rs.getString("access_key_id"),
                rs.getLong("version"), rs.getString("status"));
    }

    private OrganizationManagementController.CredentialResponse credentialResponse(CredentialRecord credential) {
        return new OrganizationManagementController.CredentialResponse(credential.id(), credential.integrationId(),
                credential.label(), credential.accessKeyId(), credential.version(), credential.status());
    }

    private static String credentialSelect() {
        return """
                SELECT id, integration_id, label, access_key_id, credential_ref, algorithm, status, expires_at, version
                  FROM integration_credentials
                """;
    }
}
