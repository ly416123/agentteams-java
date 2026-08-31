package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class TaskProgressServiceTest {
    @Test
    void calculatesProgressAndExplainsBlockedWork() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<TaskProgressService.ProgressCounts>>any(),
                eq("org-1"), eq("tenant-1"), org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getLong("completed")).thenReturn(2L);
                    when(resultSet.getLong("total")).thenReturn(4L);
                    when(resultSet.getLong("blocked")).thenReturn(1L);
                    return extractor.extractData(resultSet);
                });

        TaskProgressService service = new TaskProgressService(jdbc);
        var snapshot = service.snapshot(new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1"),
                UUID.randomUUID(), UUID.randomUUID(), "planning");

        assertThat(snapshot.phase()).isEqualTo("planning");
        assertThat(snapshot.completedCount()).isEqualTo(2);
        assertThat(snapshot.totalCount()).isEqualTo(4);
        assertThat(snapshot.progressPercent()).isEqualTo(50);
        assertThat(snapshot.waitingReason()).isEqualTo("blocked subtasks require attention");
    }
}
