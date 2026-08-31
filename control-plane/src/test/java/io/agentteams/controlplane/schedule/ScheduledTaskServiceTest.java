package io.agentteams.controlplane.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledTaskServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final ScheduledTaskScope SCOPE = new ScheduledTaskScope("org-1", "tenant-1", "project-1");

    @Test
    void createsScheduleWithNextCronWindowAndSanitizedTemplate() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        ScheduledTaskService service = new ScheduledTaskService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.insert(any(ScheduledTaskDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ScheduledTaskDefinition created = service.create(new ScheduledTaskService.CreateRequest(
                "daily-report", SCOPE, "0 0/5 * * * *", "UTC", "Report", "desc", "{\"kind\":\"report\"}",
                "manager", "api"), NOW);

        assertThat(created.nextRunAt()).isEqualTo(Instant.parse("2026-08-31T10:05:00Z"));
        assertThat(created.enabled()).isTrue();
        verify(repository).insert(created);
    }

    @Test
    void rejectsInvalidCronTimezoneAndSensitiveTemplateAttribution() {
        ScheduledTaskService service = new ScheduledTaskService(mock(ScheduledTaskRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(new ScheduledTaskService.CreateRequest(
                "bad", SCOPE, "0 * * * *", "UTC", "title", "desc", "{}", "manager", "api"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cron");
        assertThatThrownBy(() -> service.create(new ScheduledTaskService.CreateRequest(
                "bad", SCOPE, "0 0 * * * *", "not-a-zone", "title", "desc", "{}", "manager", "api"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeZone");
        assertThatThrownBy(() -> service.create(new ScheduledTaskService.CreateRequest(
                "bad", SCOPE, "0 0 * * * *", "UTC", "title", "prompt=secret", "{}", "manager", "api"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("description");
    }

    @Test
    void pauseAndResumeAreIdempotentCommands() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        UUID id = UUID.randomUUID();
        ScheduledTaskDefinition paused = definition(id, false);
        when(repository.find(SCOPE, id)).thenReturn(java.util.Optional.of(paused));
        when(repository.transition(SCOPE, id, true, false, "pause-1", NOW)).thenReturn(paused);
        when(repository.resume(SCOPE, id, "resume-1", NOW.plusSeconds(3600), NOW)).thenReturn(paused);
        ScheduledTaskService service = new ScheduledTaskService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.pause(SCOPE, id, "pause-1", NOW)).isEqualTo(paused);
        assertThat(service.resume(SCOPE, id, "resume-1", NOW)).isEqualTo(paused);
        verify(repository).transition(SCOPE, id, true, false, "pause-1", NOW);
        verify(repository).resume(SCOPE, id, "resume-1", NOW.plusSeconds(3600), NOW);
    }

    private static ScheduledTaskDefinition definition(UUID id, boolean enabled) {
        return new ScheduledTaskDefinition(id, "job", SCOPE, "0 0 * * * *", "UTC", "title", "desc", "{}",
                "manager", "api", enabled, NOW.plusSeconds(3600), null, null, 0, NOW, NOW);
    }
}
