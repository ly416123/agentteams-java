package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.schedule.ScheduledTaskDefinition;
import io.agentteams.controlplane.schedule.ScheduledTaskScope;
import io.agentteams.controlplane.schedule.ScheduledTaskService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledTaskControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final ScheduledTaskScope SCOPE = new ScheduledTaskScope("org-1", "tenant-1", "project-1");

    @Test
    void createRequiresIdempotencyKeyAndReturnsSafeScheduleSummary() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        UUID id = UUID.randomUUID();
        ScheduledTaskDefinition definition = new ScheduledTaskDefinition(id, "report", SCOPE,
                "0 0 * * * *", "UTC", "Report", "secret-free", "{}", "manager", "api", true,
                NOW.plusSeconds(3600), null, null, 0, NOW, NOW);
        when(service.create(any())).thenReturn(definition);
        ScheduledTaskController controller = new ScheduledTaskController(service);

        var response = controller.create("create-1", new ScheduledTaskController.CreateScheduleRequest(
                "report", "org-1", "tenant-1", "project-1", "0 0 * * * *", "UTC", "Report", "secret-free",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), "manager", "api"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().id()).isEqualTo(id);
        assertThat(response.getBody().title()).isEqualTo("Report");
        assertThat(response.getBody()).extracting("class").isNotNull();
        assertThatThrownBy(() -> controller.create("", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
