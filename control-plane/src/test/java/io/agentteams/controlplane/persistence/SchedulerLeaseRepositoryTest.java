package io.agentteams.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchedulerLeaseRepositoryTest {
    @Test
    void acquiresAndReleasesLeaseThroughAtomicSqlOperations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        SchedulerLeaseRepository repository = new SchedulerLeaseRepository(jdbc);
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        assertThat(repository.tryAcquire("scheduler", "pod-a", now, Duration.ofSeconds(30))).isTrue();
        assertThat(repository.release("scheduler", "pod-a", now)).isTrue();
    }
}
