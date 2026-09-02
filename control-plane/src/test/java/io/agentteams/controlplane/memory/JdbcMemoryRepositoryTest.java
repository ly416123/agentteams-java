package io.agentteams.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.MemoryPolicy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMemoryRepositoryTest {
    @Test
    void persistsRetentionAndRequiresOrganizationTenantPredicates() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(1);
        MemoryRecord memory = memory();

        assertThat(new JdbcMemoryRepository(jdbc).save(memory)).isEqualTo(memory);
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("retention_seconds")
                        && sql.contains("ON CONFLICT (id)")), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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

    @Test
    void projectQueryExcludesOtherProjectRecordsAtTheDatabaseBoundary() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        MemoryRecord memory = memory();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MemoryRecord>>any(), eq("org-1"),
                eq("tenant-1"), eq("project-1"))).thenReturn(List.of(memory));

        assertThat(new JdbcMemoryRepository(jdbc).find("org-1", "tenant-1", "project-1")).containsExactly(memory);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("project_id = ?")
                        && sql.contains("project_id IS NULL")),
                org.mockito.ArgumentMatchers.<RowMapper<MemoryRecord>>any(), eq("org-1"), eq("tenant-1"),
                eq("project-1"));
    }

    @Test
    void mapsSubjectAndTaskColumnsInMemoryPolicyFieldOrder() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MemoryRecord>>any(), eq("org-1"),
                eq("tenant-1"), eq("project-1"))).thenReturn(List.of());
        JdbcMemoryRepository repository = new JdbcMemoryRepository(jdbc);
        repository.find("org-1", "tenant-1", "project-1");

        ArgumentCaptor<RowMapper<MemoryRecord>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(anyString(), mapper.capture(), eq("org-1"), eq("tenant-1"), eq("project-1"));
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        when(resultSet.getString("scope")).thenReturn("USER_PRIVATE");
        when(resultSet.getString("organization_id")).thenReturn("org-1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-1");
        when(resultSet.getString("project_id")).thenReturn(null);
        when(resultSet.getString("team_id")).thenReturn(null);
        when(resultSet.getString("subject_id")).thenReturn("subject-1");
        when(resultSet.getObject("task_id", UUID.class)).thenReturn(null);
        when(resultSet.getString("sensitivity")).thenReturn("NORMAL");
        when(resultSet.getString("consent_status")).thenReturn("CONFIRMED");
        when(resultSet.getLong("retention_seconds")).thenReturn(3600L);
        when(resultSet.getObject("id", UUID.class)).thenReturn(id);
        when(resultSet.getString("content_ref")).thenReturn("secret://memory/test");
        when(resultSet.getString("summary")).thenReturn("summary");
        when(resultSet.getString("source")).thenReturn("test");
        when(resultSet.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
        when(resultSet.getLong("version")).thenReturn(0L);
        when(resultSet.getString("governance_status")).thenReturn("ACTIVE");

        MemoryRecord mapped = mapper.getValue().mapRow(resultSet, 0);

        assertThat(mapped.policy().subjectId()).isEqualTo("subject-1");
        assertThat(mapped.policy().taskId()).isNull();
    }

    private static MemoryRecord memory() {
        MemoryPolicy policy = new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE, "org-1", "tenant-1", null, null,
                "user-1", MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofHours(6));
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new MemoryRecord(UUID.randomUUID(), policy, "secret://memory/1", "summary", "conversation",
                now.plus(Duration.ofHours(6)), now, now, 0);
    }
}
