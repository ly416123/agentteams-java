package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDashboardAlertEventRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-25T01:00:00Z");

    @Test
    void remainsSubclassableForSpringRepositoryExceptionTranslation() {
        assertThat(Modifier.isFinal(JdbcDashboardAlertEventRepository.class.getModifiers())).isFalse();
    }

    @Test
    void doesNotReclaimAnActiveFingerprint() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThat(new JdbcDashboardAlertEventRepository(jdbc).claim(event(), NOW)).isEmpty();
        verify(jdbc).update(contains("ON CONFLICT (fingerprint) DO NOTHING"), any(Object[].class));
        verify(jdbc).update(contains("status = 'PENDING'"), any(Object[].class));
    }

    @Test
    void writesTerminalDeliveryStateAndFailureDetails() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        JdbcDashboardAlertEventRepository repository = new JdbcDashboardAlertEventRepository(jdbc);
        UUID id = event().id();

        repository.markSent(id, NOW);
        repository.markFailed(id, NOW.plusSeconds(60), "webhook unavailable", NOW);

        verify(jdbc).update(contains("status = 'SENT'"), any(Object[].class));
        verify(jdbc).update(contains("status = 'FAILED'"), any(Object[].class));
    }

    private static DashboardAlertEvent event() {
        DashboardAlertService.Alert alert = new DashboardAlertService.Alert(
                "COST", "WARNING", 2, "estimated model cost exceeded configured threshold");
        return DashboardAlertEvent.pending("fingerprint", "tenant-a", "project-a", alert,
                NOW.minusSeconds(3600), NOW, NOW);
    }
}
