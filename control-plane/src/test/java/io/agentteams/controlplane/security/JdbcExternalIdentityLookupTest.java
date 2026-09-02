package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcExternalIdentityLookupTest {
    @Test
    void lookupIsScopedToIntegrationAndExternalIdentityTuple() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq("integration-1"), eq("acme-corp"), eq("ding-001")))
                .thenReturn(List.of(new ExternalIdentity(userId, ExternalIdentity.Status.ACTIVE, "acme-corp", "ding-001")));

        var result = new JdbcExternalIdentityLookup(jdbc)
                .findByIntegrationIdAndExternalOrganizationIdAndExternalUserId("integration-1", "acme-corp", "ding-001");

        assertThat(result).isPresent();
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("integration_id = ?::uuid")
                        && sql.contains("external_organization_id = ?") && sql.contains("external_user_id = ?")),
                any(RowMapper.class), eq("integration-1"), eq("acme-corp"), eq("ding-001"));
    }
}
