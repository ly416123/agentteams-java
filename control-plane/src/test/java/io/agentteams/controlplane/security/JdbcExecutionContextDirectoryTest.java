package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcExecutionContextDirectoryTest {
    @Test
    void resolvesLegacyTenantAndRequiresTheAuthenticatedMembership() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ExecutionContext expected = new ExecutionContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "project-1", "team-1", "user-1");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ExecutionContext>>any(),
                eq("legacy-tenant"), eq("legacy-tenant"), eq("legacy-tenant"), eq("user-1"), eq("user-1")))
                .thenReturn(List.of(expected));

        JdbcExecutionContextDirectory directory = new JdbcExecutionContextDirectory(jdbc);

        assertThat(directory.resolve("legacy-tenant", "project-1", "team-1", "user-1"))
                .contains(expected);
    }

    @Test
    void returnsEmptyWhenMappingOrMembershipDoesNotExist() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ExecutionContext>>any(),
                eq("unknown"), eq("unknown"), eq("unknown"), eq("user-1"), eq("user-1"))).thenReturn(List.of());

        assertThat(new JdbcExecutionContextDirectory(jdbc).resolve("unknown", "project-1", "team-1", "user-1"))
                .isEmpty();
    }
}
