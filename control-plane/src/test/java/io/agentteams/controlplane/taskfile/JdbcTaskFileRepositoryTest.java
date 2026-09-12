package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTaskFileRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private JdbcTaskFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcTaskFileRepository(jdbc);
    }

    @Test
    void insertBindsAllColumns() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        TaskFileRecord record = new TaskFileRecord(UUID.randomUUID(), UUID.randomUUID(), null,
                TaskFileRecord.OUTPUT, "报告.pdf", "application/pdf", 3, "ab".repeat(32),
                "tasks/t/files/f/报告.pdf", null, null, TaskFileRecord.AVAILABLE, now, now);
        assertThat(repository.insert(record)).isTrue();
    }

    @Test
    void findDedupQueriesByTaskRoleNameSha() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<TaskFileRecord>of());
        repository.findDedup(UUID.randomUUID(), TaskFileRecord.OUTPUT, "a.pdf", "cd".repeat(32));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("role = ?", "name = ?", "sha256 = ?");
    }

    @Test
    void findAvailableOutputsScansRandomWindow() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<TaskFileRecord>of());
        repository.findAvailableOutputs(200);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        // 概率轮转窗口：固定 ORDER BY created_at 会让 AVAILABLE 总量超过批大小时
        // 较新记录永远得不到探测（固定窗口饥饿）。
        assertThat(sql.getValue()).contains("role = 'OUTPUT'", "status = 'AVAILABLE'", "random()", "LIMIT ?");
    }

    @Test
    void findByIdReturnsRecordViaMapper() throws java.sql.SQLException {
        TaskFileRecord record = repository.recordMapper()
                .mapRow(recordFixtureResultSet(), 0);
        assertThat(record).isNotNull();
        assertThat(record.role()).isEqualTo(TaskFileRecord.OUTPUT);
    }

    private java.sql.ResultSet recordFixtureResultSet() throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("task_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("attempt_id", UUID.class)).thenReturn(null);
        when(rs.getString("role")).thenReturn("OUTPUT");
        when(rs.getString("name")).thenReturn("a.pdf");
        when(rs.getString("content_type")).thenReturn("application/pdf");
        when(rs.getLong("size_bytes")).thenReturn(3L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString("sha256")).thenReturn("ab".repeat(32));
        when(rs.getString("storage_key")).thenReturn("tasks/t/files/f/a.pdf");
        when(rs.getObject("source_session_id", UUID.class)).thenReturn(null);
        when(rs.getObject("source_file_id", UUID.class)).thenReturn(null);
        when(rs.getString("status")).thenReturn("AVAILABLE");
        when(rs.getObject("created_at", Instant.class)).thenReturn(Instant.EPOCH);
        when(rs.getObject("updated_at", Instant.class)).thenReturn(Instant.EPOCH);
        return rs;
    }
}
