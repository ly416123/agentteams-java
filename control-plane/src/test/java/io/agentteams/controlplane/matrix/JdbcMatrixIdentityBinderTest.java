package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMatrixIdentityBinderTest {

    @Test
    void bindsMatrixSenderToImmutablePlatformIdentity() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row("@alice:example.org", "alice", "tenant-a", "project-a", "team-a",
                "[\"TASK_CREATE\",\"TEAM_APPROVE\"]");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        Optional<MatrixIdentity> identity = new JdbcMatrixIdentityBinder(jdbc)
                .bind("@alice:example.org");

        assertThat(identity).isPresent();
        assertThat(identity.orElseThrow().matrixUserId()).isEqualTo("@alice:example.org");
        assertThat(identity.orElseThrow().subject()).isEqualTo("alice");
        assertThat(identity.orElseThrow().scope().tenant()).isEqualTo("tenant-a");
        assertThat(identity.orElseThrow().permissions())
                .containsExactlyInAnyOrder("TASK_CREATE", "TEAM_APPROVE");
        assertThatThrownBy(() -> identity.orElseThrow().permissions().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsEmptyForUnknownOrInvalidSender() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("@unknown:example.org")))
                .thenReturn(List.of());

        JdbcMatrixIdentityBinder binder = new JdbcMatrixIdentityBinder(jdbc);

        assertThat(binder.bind("@unknown:example.org")).isEmpty();
        assertThat(binder.bind(" ")).isEmpty();
    }

    private static ResultSet row(String matrixUserId, String subject, String tenant, String project,
            String team, String permissions) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("matrix_user_id")).thenReturn(matrixUserId);
        when(row.getString("subject")).thenReturn(subject);
        when(row.getString("tenant")).thenReturn(tenant);
        when(row.getString("project")).thenReturn(project);
        when(row.getString("team")).thenReturn(team);
        when(row.getString("permissions")).thenReturn(permissions);
        when(row.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")));
        return row;
    }
}
