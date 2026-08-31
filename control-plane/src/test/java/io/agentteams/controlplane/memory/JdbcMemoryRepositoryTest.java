package io.agentteams.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.MemoryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMemoryRepositoryTest {
    @Test
    void persistsRetentionAndRequiresOrganizationTenantPredicates() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(1);
        MemoryRecord memory = memory();

        assertThat(new JdbcMemoryRepository(jdbc).save(memory)).isEqualTo(memory);
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("retention_seconds")
                        && sql.contains("ON CONFLICT (id)")), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void queryIsTenantScopedAtTheDatabaseBoundary() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        MemoryRecord memory = memory();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MemoryRecord>>any(), eq("org-1"), eq("tenant-1")))
                .thenReturn(List.of(memory));

        assertThat(new JdbcMemoryRepository(jdbc).find("org-1", "tenant-1")).containsExactly(memory);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("organization_id = ?")
                        && sql.contains("tenant_id = ?")), org.mockito.ArgumentMatchers.<RowMapper<MemoryRecord>>any(),
                eq("org-1"), eq("tenant-1"));
    }

    private static MemoryRecord memory() {
        MemoryPolicy policy = new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE, "org-1", "tenant-1", null, null,
                "user-1", MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofHours(6));
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new MemoryRecord(UUID.randomUUID(), policy, "secret://memory/1", "summary", "conversation",
                now.plus(Duration.ofHours(6)), now, now, 0);
    }
}
