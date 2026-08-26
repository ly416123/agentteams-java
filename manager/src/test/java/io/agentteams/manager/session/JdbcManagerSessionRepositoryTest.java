package io.agentteams.manager.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcManagerSessionRepositoryTest {
    @Test
    void storesSessionWithScopeAndIdempotencyKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.contains("INSERT INTO manager_sessions"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        JdbcManagerSessionRepository repository = new JdbcManagerSessionRepository(jdbc);
        ManagerSessionRecord session = ManagerSessionRecord.newSession(UUID.randomUUID(), "tenant-a", "project-a",
                "actor-a", Instant.parse("2026-08-26T00:00:00Z"));

        repository.insertSession(session, "session-key");

        verify(jdbc).update(contains("INSERT INTO manager_sessions"), eq(session.id()), eq("tenant-a"),
                eq("project-a"), eq("actor-a"), eq("ACTIVE"), eq(0L), eq("session-key"),
                eq(session.createdAt()), eq(session.updatedAt()));
    }

    @Test
    void updatesSessionOnlyWhenExpectedVersionMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcManagerSessionRepository repository = new JdbcManagerSessionRepository(jdbc);
        UUID sessionId = UUID.randomUUID();
        org.mockito.Mockito.when(jdbc.query(contains("COALESCE(MAX(cursor)"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                eq(sessionId))).thenReturn(java.util.List.of(1L));

        org.mockito.Mockito.when(jdbc.update(contains("UPDATE manager_sessions"),
                eq("CANCELLED"), org.mockito.ArgumentMatchers.any(), eq(sessionId), eq(0L))).thenReturn(1);
        org.mockito.Mockito.when(jdbc.query(contains("FROM manager_sessions"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ManagerSessionRecord>>any(),
                eq(sessionId))).thenReturn(java.util.List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.updateSession(sessionId, 0,
                ManagerSessionRecord.Status.CANCELLED, Instant.parse("2026-08-26T00:00:00Z")))
                .isInstanceOf(ManagerSessionNotFoundException.class);
    }

    @Test
    void appendsEventUsingDatabaseCursorAndSupportsReplayQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcManagerSessionRepository repository = new JdbcManagerSessionRepository(jdbc);
        UUID sessionId = UUID.randomUUID();

        repository.appendEvent(sessionId, "SESSION_CANCELLED", "{\"status\":\"CANCELLED\"}",
                Instant.parse("2026-08-26T00:00:00Z"));

        verify(jdbc).update(contains("INSERT INTO manager_events"), eq(sessionId), eq(1L),
                org.mockito.ArgumentMatchers.isNull(), eq("SESSION_CANCELLED"),
                eq("{\"status\":\"CANCELLED\"}"), eq(Instant.parse("2026-08-26T00:00:00Z")));
        repository.findEventsAfter(sessionId, 0);
        verify(jdbc).query(contains("FROM manager_events"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ManagerEventRecord>>any(),
                eq(sessionId), eq(0L));
    }
}
