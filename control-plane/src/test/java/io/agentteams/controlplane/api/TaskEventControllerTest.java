package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.DomainEventRecord;
import io.agentteams.controlplane.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskEventControllerTest {
    @Test
    void resumesAfterLastEventIdAndRedactsSensitivePayloadFields() throws Exception {
        TaskService service = mock(TaskService.class);
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        DomainEventRecord event = DomainEventRecord.create(UUID.randomUUID(), "task", taskId, "TaskQueued",
                "{\"safe\":\"ok\",\"token\":\"secret-token\",\"containerLog\":\"private log\","
                        + "\"secretGeneration\":\"private-generation\",\"accessToken\":\"private-token\"}", now, 8);
        when(service.events(eq(taskId), anyLong())).thenReturn(List.of(event));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskEventController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(get("/api/v1/tasks/{id}/events", taskId).header("Last-Event-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id: 8")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[REDACTED]")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private log"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-generation"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-token"))));
    }
}
