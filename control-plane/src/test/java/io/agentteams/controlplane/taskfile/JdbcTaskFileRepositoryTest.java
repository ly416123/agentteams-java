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
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains(
                "task_id", "role", "name", "content_type", "size_bytes", "sha256",
                "storage_key", "source_session_id", "source_file_id", "status",
                "created_at", "updated_at");
        // 14 列全绑定；时间列必须显式包 Timestamp（PG JDBC 无法推断 Instant，
        // 回退为直接传 Instant 仅在真实 DB 运行时炸——kind 验收缺陷 23d96fe）。
        Object[] bound = args.getValue();
        assertThat(bound).hasSize(14);
        assertThat(bound[12]).isEqualTo(java.sql.Timestamp.from(now));
        assertThat(bound[13]).isEqualTo(java.sql.Timestamp.from(now));
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
        assertThat(record.attemptId()).isNull();
        assertThat(record.createdAt()).isEqualTo(Instant.EPOCH);
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
        when(rs.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.from(Instant.EPOCH));
        when(rs.getTimestamp("updated_at")).thenReturn(java.sql.Timestamp.from(Instant.EPOCH));
        return rs;
    }
}
