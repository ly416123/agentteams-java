package io.agentteams.controlplane.matrix;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatrixOutboundRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @Test
    void requeuesExpiredClaimsBeforeClaimingPendingMessages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MatrixOutboundRepository repository = new MatrixOutboundRepository(jdbc);

        repository.claimDue(NOW, 10);

        verify(jdbc).update(contains("delivery claim expired"), eq(Timestamp.from(NOW)),
                eq(Timestamp.from(NOW)), eq(Timestamp.from(NOW.minus(2, ChronoUnit.MINUTES))));
        verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
