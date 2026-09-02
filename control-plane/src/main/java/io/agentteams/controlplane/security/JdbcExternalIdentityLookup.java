package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database boundary used by SDK authentication to resolve an integration's external user mapping. */
@Repository
public class JdbcExternalIdentityLookup implements SdkAuthenticationFilter.ExternalIdentityLookup {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcExternalIdentityLookup(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcExternalIdentityLookup(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ExternalIdentity> findByIntegrationIdAndExternalOrganizationIdAndExternalUserId(
            String integrationId, String externalOrganizationId, String externalUserId) {
        return jdbc.query("""
                SELECT id, internal_user_id, status, external_organization_id, external_user_id
                  FROM external_identities
                 WHERE integration_id = ?::uuid
                   AND external_organization_id = ?
                   AND external_user_id = ?
                """, (rs, row) -> new ExternalIdentity(rs.getObject("internal_user_id", UUID.class),
                ExternalIdentity.Status.valueOf(rs.getString("status")), rs.getString("external_organization_id"),
                rs.getString("external_user_id")), integrationId, externalOrganizationId, externalUserId)
                .stream().findFirst();
    }
}
