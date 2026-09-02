package io.agentteams.controlplane.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves credential metadata from PostgreSQL and material only through the deployment-owned secret port. */
@Repository
public class JdbcIntegrationCredentialLookup implements SdkAuthenticationFilter.IntegrationCredentialLookup {
    private final JdbcTemplate jdbc;
    private final CredentialSecretProvider secrets;
    private final Clock clock;

    @Autowired
    public JdbcIntegrationCredentialLookup(DataSource dataSource, CredentialSecretProvider secrets, Clock clock) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")), secrets, clock);
    }

    JdbcIntegrationCredentialLookup(JdbcTemplate jdbc, CredentialSecretProvider secrets, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<IntegrationCredential> findActiveByAccessKeyId(String accessKeyId) {
        return jdbc.query("""
                SELECT access_key_id, credential_ref, algorithm, status, expires_at, integration_id, organization_id
                  FROM integration_credentials c
                  JOIN integrations i ON i.id = c.integration_id
                 WHERE c.access_key_id = ?
                   AND c.status = 'ACTIVE'
                   AND i.status = 'ACTIVE'
                   AND (c.expires_at IS NULL OR c.expires_at > ?)
                """, (rs, row) -> metadata(rs), accessKeyId, Timestamp.from(clock.instant())).stream()
                .findFirst().flatMap(this::materialize);
    }

    private CredentialMetadata metadata(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new CredentialMetadata(rs.getString("access_key_id"), rs.getString("credential_ref"),
                SignatureAlgorithm.valueOf(rs.getString("algorithm")),
                expiresAt == null ? Instant.MAX : expiresAt.toInstant(), rs.getObject("integration_id", java.util.UUID.class).toString(),
                rs.getObject("organization_id", java.util.UUID.class).toString());
    }

    private Optional<IntegrationCredential> materialize(CredentialMetadata metadata) {
        return secrets.resolve(metadata.credentialRef()).map(secret -> new IntegrationCredential(metadata.accessKeyId(),
                secret, metadata.algorithm(), true, metadata.expiresAt(), metadata.integrationId(), metadata.organizationId()));
    }

    private record CredentialMetadata(String accessKeyId, String credentialRef, SignatureAlgorithm algorithm,
            Instant expiresAt, String integrationId, String organizationId) { }
}
