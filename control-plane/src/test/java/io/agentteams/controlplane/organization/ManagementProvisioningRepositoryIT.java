package io.agentteams.controlplane.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.agentteams.controlplane.security.SignatureAlgorithm;

@Testcontainers(disabledWithoutDocker = true)
class ManagementProvisioningRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcOrganizationManagementRepository repository;

    @BeforeEach
    void migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcOrganizationManagementRepository(dataSource);
    }

    @Test
    void provisionsUpdatesDisablesAndListsMembershipsWithoutCrossIdentityLeakage() {
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        UUID organizationId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        repository.insertOrganization(organizationId, "acme", "Acme", now);
        repository.insertIntegration(integrationId, organizationId, "sdk", "SDK", now);

        var credential = repository.insertCredentialIdempotent(UUID.randomUUID(), integrationId, "primary", "AKIA-1",
                "secret://sdk/primary", SignatureAlgorithm.HMAC_SHA256, null, "credential-create-1", "credential-hash-1",
                now);
        var repeatedCredential = repository.insertCredentialIdempotent(UUID.randomUUID(), integrationId, "primary", "AKIA-2",
                "secret://sdk/other", SignatureAlgorithm.HMAC_SHA256, null, "credential-create-1", "credential-hash-1",
                now.plusSeconds(1));
        assertThat(repeatedCredential).isEqualTo(credential);
        var rotatedCredential = repository.updateCredentialIdempotent(credential.id(), credential.version(), "AKIA-3",
                "secret://sdk/rotated", "ACTIVE", null, "ROTATE_MANAGEMENT_CREDENTIAL", "credential-rotate-1",
                "credential-hash-2", now.plusSeconds(2));
        assertThat(repository.updateCredentialIdempotent(credential.id(), credential.version(), "AKIA-4",
                "secret://sdk/other-rotation", "ACTIVE", null, "ROTATE_MANAGEMENT_CREDENTIAL", "credential-rotate-1",
                "credential-hash-2", now.plusSeconds(3))).isEqualTo(rotatedCredential);
        var revokedCredential = repository.updateCredentialIdempotent(credential.id(), rotatedCredential.version(),
                rotatedCredential.accessKeyId(), "secret://sdk/rotated", "REVOKED", null,
                "REVOKE_MANAGEMENT_CREDENTIAL", "credential-revoke-1", "credential-hash-3", now.plusSeconds(4));
        assertThat(revokedCredential.status()).isEqualTo("REVOKED");

        var created = repository.provisionExternalUser(integrationId, organizationId, "acme-corp", "alice",
                "Alice", "provision-1", "hash-1", now);
        var repeated = repository.provisionExternalUser(integrationId, organizationId, "acme-corp", "alice",
                "Alice Changed", "provision-1", "hash-1", now.plusSeconds(1));
        assertThat(repeated.internalUserId()).isEqualTo(created.internalUserId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM management_users", Long.class)).isEqualTo(1L);

        jdbc.update("""
                INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
                SELECT ?, subject, 'ADMIN', ?, ? FROM management_users WHERE id = ?
                """, organizationId, Timestamp.from(now), Timestamp.from(now), created.internalUserId());
        assertThat(repository.listProvisionedUserMemberships(integrationId, organizationId, "acme-corp", "alice"))
                .singleElement().satisfies(membership -> {
                    assertThat(membership.scopeType()).isEqualTo("organization");
                    assertThat(membership.role()).isEqualTo("ADMIN");
                });

        var updated = repository.updateProvisionedUser(integrationId, "acme-corp", "alice", "Alice Updated",
                "update-1", "hash-2", now.plusSeconds(2));
        assertThat(updated.displayName()).isEqualTo("Alice Updated");
        var disabled = repository.disableProvisionedUser(integrationId, "acme-corp", "alice", "disable-1", "hash-3",
                now.plusSeconds(3));
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> repository.listProvisionedUserMemberships(
                integrationId, organizationId, "other-corp", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provisioned user not found");
    }
}
