package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcIntegrationCredentialLookupTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void unavailableSecretProviderFailsClosedAfterMetadataLookup() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("atk-1"), any())).thenReturn(List.of());

        assertThat(new JdbcIntegrationCredentialLookup(jdbc, new UnavailableCredentialSecretProvider(), CLOCK)
                .findActiveByAccessKeyId("atk-1")).isEmpty();
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("c.status = 'ACTIVE'")
                        && sql.contains("i.status = 'ACTIVE'") && sql.contains("credential_ref")),
                any(RowMapper.class), eq("atk-1"), any());
    }
}
